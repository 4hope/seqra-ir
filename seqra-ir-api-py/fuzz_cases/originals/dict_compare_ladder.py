def subject(a, b, c):
    data = {"x": a, "y": b, "z": c}
    if data["x"] < data["y"]:
        data["x"] = data["x"] + data["z"]
    elif data["x"] == data["y"]:
        data["x"] = data["x"] - data["z"]
    else:
        data["x"] = data["x"] + data["y"]

    if data["x"] < 0:
        return 0 - data["x"]
    return data["x"] + data["z"]
