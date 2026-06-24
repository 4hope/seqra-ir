def subject(a, b, c):
    if b == 0:
        seed = 0
    else:
        seed = (a // b) + (a % b)

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
        if seed < 0:
            total = total - seed
        else:
            total = total + seed
        total = total + i
        i = i + 1

    if total < 0:
        return 0 - total
    return total
