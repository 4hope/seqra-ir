def subject(a, b, c):
    mixed = a + b
    if c < 0:
        seed = a - b
    else:
        seed = a + b

    total = 0
    limit = c
    if limit < 0:
        limit = 0 - limit
    if limit < 2:
        limit = limit + 2
    else:
        limit = 4
    step = 0
    while step < limit:
        if seed < 0:
            total = total - mixed
        else:
            total = total + mixed
        total = total + step
        step = step + 1

    if total < 0:
        return 0 - total
    return total + seed
