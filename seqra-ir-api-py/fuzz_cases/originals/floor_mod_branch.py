def subject(a, b):
    if b == 0:
        return 0
    value = (a // b) - (a % b)
    if value < 0:
        return 0 - value
    return value
