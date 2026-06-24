def subject(a, b):
    data = {"x": a, "y": b}
    total = data["x"] + data["y"]
    if total < 0:
        data["x"] = 0 - total
    else:
        data["x"] = total
    return data["x"]
