def subject(a, b, c):
    data = [a, b, a + b]
    data[1] = data[0] * c
    return data[1] - data[2]
