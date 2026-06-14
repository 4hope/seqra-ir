def subject(a, b):
    total = 0
    i = 0
    limit = (a & 3) + 1
    while i < limit:
        total = total + b
        i = i + 1
    return total
