def subject(a, b, c):
    flag = a < b
    total = 0
    limit = c
    if limit < 0:
        limit = 0 - limit
    if limit < 2:
        limit = limit + 2
    else:
        limit = 4

    i = 0
    while i < limit:
        if flag:
            total = total + a + i
        else:
            total = total + b - i
        flag = not flag
        i = i + 1

    if total < 0:
        return 0 - total
    return total
