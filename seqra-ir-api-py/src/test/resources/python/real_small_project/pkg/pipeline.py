import pkg.models as pkg_models


def normalize(value: int) -> int:
    if value < 0:
        return 0 - value
    return value + 1


def process(a: int, b: int, c: int) -> int:
    counter = pkg_models.Counter(a)
    total = counter.bump(b)
    data = {"left": total, "right": c}
    if data["right"] < 0:
        data["left"] = data["left"] - data["right"]
    else:
        data["left"] = data["left"] + data["right"]

    limit = normalize(c)
    if limit < 2:
        limit = limit + 1
    else:
        limit = 3

    step = 0
    result = 0
    while step < limit:
        result = result + data["left"]
        step = step + 1
    return result
