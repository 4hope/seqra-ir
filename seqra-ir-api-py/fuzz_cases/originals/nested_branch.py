def subject(a, b):
    if a < 0:
        if b < 0:
            return (-a) + (-b)
        return (-a) + b
    if b < 0:
        return a + (-b)
    return a + b
