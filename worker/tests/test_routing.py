from cascadeserve_worker.routing import feature_vector, prompt_features


def test_prompt_features_match_router_contract():
    prompt = "Solve (2 + 3)\nnow"

    assert prompt_features(prompt) == {
        "word_count": 5.0,
        "char_count": 17.0,
        "digit_count": 2.0,
        "symbol_count": 3.0,
        "newline_count": 1.0,
    }


def test_feature_vector_has_stable_order():
    assert feature_vector("abc 12") == [2.0, 6.0, 2.0, 0.0, 0.0]
