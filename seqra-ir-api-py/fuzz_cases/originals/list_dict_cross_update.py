def subject(a, b, c):
    items = [a, b, a + b]
    data = {"left": a, "right": c}
    items[0] = items[1] + items[2]
    data["left"] = data["left"] + items[0]

    if data["right"] < 0:
        data["left"] = data["left"] - data["right"]
    else:
        data["left"] = data["left"] + data["right"]

    if data["left"] < 0:
        return 0 - data["left"]
    return data["left"]
