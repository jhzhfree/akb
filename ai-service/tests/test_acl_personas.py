def visible(doc_tags, caller_tags):
    return not doc_tags or bool(set(doc_tags) & set(caller_tags))


def test_anonymous_team_a_team_b_admin_leakage_matrix():
    docs = {
        "public": [],
        "team-a": ["team-a"],
        "team-b": ["team-b"],
        "finance": ["finance"],
    }
    personas = {
        "anonymous": [],
        "team-a-user": ["team-a"],
        "team-b-user": ["team-b"],
        "admin": ["team-a", "team-b", "finance"],
    }
    expected = {
        "anonymous": {"public"},
        "team-a-user": {"public", "team-a"},
        "team-b-user": {"public", "team-b"},
        "admin": set(docs),
    }
    for persona, tags in personas.items():
        actual = {name for name, acl in docs.items() if visible(acl, tags)}
        assert actual == expected[persona]
