def subject(a, b, c):
    if a < b:
        base = a + b
    elif a == b:
        base = a - b
    else:
        base = a + c

    total = 0
    steps = c
    if steps < 0:
        steps = 0 - steps
    if steps < 2:
        steps = steps + 2
    else:
        steps = 4

    i = 0
    while i < steps:
        if base < 0:
            total = total - base
        else:
            total = total + base
        i = i + 1

    return total + i
