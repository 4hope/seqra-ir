def subject(a, b):
    total = 0
    i = 0
    limit = (a & 3) + 2
    while i < limit:
        if b < 0:
            total = total - 1
        else:
            total = total + 1
        i = i + 1
    return total
