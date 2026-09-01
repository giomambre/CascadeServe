FEATURE_NAMES = (
    "word_count",
    "char_count",
    "digit_count",
    "symbol_count",
    "newline_count",
)


def prompt_features(prompt: str) -> dict[str, float]:
    return {
        "word_count": float(len(prompt.split())),
        "char_count": float(len(prompt)),
        "digit_count": float(sum(character.isdigit() for character in prompt)),
        "symbol_count": float(
            sum(character in "{}[]()=+-*/" for character in prompt)
        ),
        "newline_count": float(prompt.count("\n")),
    }


def feature_vector(prompt: str) -> list[float]:
    features = prompt_features(prompt)
    return [features[name] for name in FEATURE_NAMES]
