class Accumulator:
    def __init__(self, value, step):
        self.value = value
        self.step = step
        self.count = 0

    def advance(self, delta):
        self.value = self.value + delta + self.step
        self.count = self.count + 1
        return self.value


def subject(a, b, c):
    acc = Accumulator(a, b)
    total = 0
    limit = c
    if limit < 0:
        limit = 0 - limit
    if limit < 2:
        limit = limit + 2
    else:
        limit = 4

    while acc.count < limit:
        current = acc.advance(c)
        if current < 0:
            total = total - current
        else:
            total = total + current

    if total < 0:
        return 0 - total
    return total + acc.step
