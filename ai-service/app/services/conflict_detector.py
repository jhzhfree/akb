"""Deterministic claim-conflict candidate detection used before AI/human adjudication."""

import re
from collections import defaultdict
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

        negative_left = any(word in object_left for word in NEGATIVE_WORDS)
        negative_right = any(word in object_right for word in NEGATIVE_WORDS)
        opposite = negative_left != negative_right
        score = 0.92 if opposite else 0.68
        conflict_type = "POLARITY_CONFLICT" if opposite else "VALUE_CONFLICT"

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
            "status": "OPEN",
        }
