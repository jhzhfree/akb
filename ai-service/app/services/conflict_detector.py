"""Deterministic claim-conflict candidate detection used before AI/human adjudication."""

import re
from collections import defaultdict
from datetime import datetime
from typing import Any, Dict, Iterable, List, Optional, Tuple

NEGATIVE_WORDS = {"不", "不能", "不支持", "禁止", "不得", "无需", "无"}


def normalize(text: Any) -> str:
    """Normalize claim text for deterministic conflict grouping."""
    return re.sub(r"\s+", "", str(text or "")).lower()


def claim_code(claim: Dict[str, Any]) -> Optional[str]:
    """Return a stable identifier for a claim candidate."""
    value = claim.get("claimCode") or claim.get("claim_code") or claim.get("id")
    return str(value) if value is not None else None


class ConflictDetector:
    """Detect conflicts only inside comparable subject/predicate buckets.

    The previous implementation compared every claim with every other claim.
    Grouping first changes the common case from O(N^2) comparisons to the sum
    of O(group_size^2), which is substantially smaller for enterprise KBs.
    """

    def detect(
        self,
        claims: List[Dict[str, Any]],
        focus_codes: Optional[Iterable[str]] = None,
    ) -> List[Dict[str, Any]]:
        focus = {str(code) for code in (focus_codes or []) if code is not None}
        groups: Dict[Tuple[str, str], List[Dict[str, Any]]] = defaultdict(list)

        for item in claims:
            subject = normalize(item.get("subject"))
            predicate = normalize(item.get("predicate"))
            if subject:
                groups[(subject, predicate)].append(item)

        conflicts = []
        seen = set()
        for group in groups.values():
            for index, left in enumerate(group):
                left_code = claim_code(left)
                for right in group[index + 1 :]:
                    right_code = claim_code(right)
                    if focus and left_code not in focus and right_code not in focus:
                        continue
                    pair = tuple(sorted((left_code or "", right_code or "")))
                    if pair in seen:
                        continue
                    seen.add(pair)
                    result = self.compare(left, right)
                    if result:
                        conflicts.append(result)

        return sorted(conflicts, key=lambda item: item["conflictScore"], reverse=True)

    def compare(self, left: Dict[str, Any], right: Dict[str, Any]):
        subject_left = normalize(left.get("subject"))
        subject_right = normalize(right.get("subject"))
        predicate_left = normalize(left.get("predicate"))
        predicate_right = normalize(right.get("predicate"))
        if not subject_left or subject_left != subject_right:
            return None
        if predicate_left and predicate_right and predicate_left != predicate_right:
            return None

        object_left = normalize(left.get("object") or left.get("objectValue") or left.get("object_value"))
        object_right = normalize(right.get("object") or right.get("objectValue") or right.get("object_value"))
        if object_left == object_right:
            return None

        temporal = self.validity_relation(left, right)
        if temporal == "DISJOINT":
            # Design V1.1 §6 / §17: two statements valid in disjoint windows describe successive
            # versions of reality. That is supersession, not contradiction, and must not reach
            # the human conflict queue as a conflict.
            return None

        negative_left = self.polarity(left, object_left)
        negative_right = self.polarity(right, object_right)
        opposite = negative_left != negative_right
        score = 0.92 if opposite else 0.68
        conflict_type = "POLARITY_CONFLICT" if opposite else "VALUE_CONFLICT"
        if temporal == "OVERLAPPING":
            # Only part of their validity windows collide; keep it reviewable but weaker.
            score = round(score * 0.85, 4)

        code_left = claim_code(left)
        code_right = claim_code(right)
        if code_left and code_right and code_left > code_right:
            left, right = right, left
            code_left, code_right = code_right, code_left

        return {
            "objectACode": code_left,
            "objectBCode": code_right,
            "subject": left.get("subject"),
            "predicate": left.get("predicate"),
            "objectA": left.get("object") or left.get("objectValue") or left.get("object_value"),
            "objectB": right.get("object") or right.get("objectValue") or right.get("object_value"),
            "conflictType": conflict_type,
            "conflictScore": score,
            "temporal": temporal,
            "status": "OPEN",
        }

    @staticmethod
    def polarity(claim: Dict[str, Any], object_text: str) -> bool:
        """Negative when the model stated it explicitly, otherwise fall back to wording."""
        explicit = str(claim.get("polarity") or claim.get("polarityValue") or "").strip().upper()
        if explicit in ("POSITIVE", "NEGATIVE"):
            return explicit == "NEGATIVE"
        return any(word in object_text for word in NEGATIVE_WORDS)

    @classmethod
    def validity_relation(cls, left: Dict[str, Any], right: Dict[str, Any]) -> str:
        """Classify how the validity windows of two claims relate."""
        left_window = cls._window(left)
        right_window = cls._window(right)
        if not left_window or not right_window:
            return "UNKNOWN"
        left_start, left_end = left_window
        right_start, right_end = right_window
        disjoint = left_end < right_start or right_end < left_start
        return "DISJOINT" if disjoint else "OVERLAPPING"

    @staticmethod
    def _window(claim: Dict[str, Any]):
        """Return (start, end) as naive datetimes; open bounds use datetime bounds."""
        start = ConflictDetector._moment(claim, "validFrom", "valid_from")
        end = ConflictDetector._moment(claim, "validTo", "valid_to")
        if not start and not end:
            return None
        return start or datetime.min, end or datetime.max

    @staticmethod
    def _moment(claim: Dict[str, Any], *keys):
        for key in keys:
            raw = claim.get(key)
            if not raw:
                continue
            try:
                text = str(raw).replace("Z", "+00:00")
                moment = datetime.fromisoformat(text)
                return moment.replace(tzinfo=None) if moment.tzinfo else moment
            except ValueError:
                continue
        return None
