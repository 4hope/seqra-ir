def subject(a, b, c):
    data = (a, b, a + b)
    total = 0
    limit = c
    if limit < 0:
        limit = 0 - limit
    if limit < 3:
        limit = limit + 1
    else:
        limit = 4

    i = 0
    while i < limit:
        if i < 2:
            total = total + data[i]
        else:
            total = total + data[2]
        i = i + 1

    if total < 0:
        return 0 - total
    return total
