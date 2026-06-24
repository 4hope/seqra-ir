def subject(a, b):
    mixed = ((a << 1) ^ (b >> 2)) & 255
    if mixed < 64:
        return mixed + a
    return mixed - b
