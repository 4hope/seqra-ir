def subject(a, b):
    if a < b:
        if a < 0:
            return a + b
        return b - a
    if a == b:
        return 0
    return a - b
