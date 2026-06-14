def subject(a, b):
    total = a
    steps = (b & 3) + 1
    while steps > 0:
        total = total - 1
        steps = steps - 1
    return total
