def subject(a, b, c):
    data = {"left": a, "right": b}
    items = [a, b, a + b]
    items[1] = items[0] + items[2]
    items[2] = items[1] - c

    if data["right"] < 0:
        data["left"] = data["left"] - data["right"]
    else:
        data["left"] = data["left"] + data["right"]

    total = 0
    i = 0
    limit = c
    if limit < 0:
        limit = 0 - limit
    if limit < 2:
        limit = limit + 1
    else:
        limit = 3

    while i < limit:
        total = total + data["left"]
        if items[2] < 0:
            total = total - 1
        else:
            total = total + 1
        i = i + 1

    return total + items[0]
