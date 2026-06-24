def subject(a, b, c):
    data = {"left": a, "right": b}

    if c < 0:
        data["right"] = data["right"] - c
    else:
        data["right"] = data["right"] + c

    limit = c
    if limit < 0:
        limit = 0 - limit
    if limit < 2:
        limit = limit + 1
    else:
        limit = 3

    total = 0
    i = 0
    while i < limit:
        total = total + data["left"]
        if data["right"] < 0:
            total = total - 1
        else:
            total = total + 1
        i = i + 1

    total = total + data["left"]
    if total < 0:
        total = 0 - total
    return total
