class PairBox:
    def __init__(self, left, right):
        self.left = left
        self.right = right

    def total(self):
        return self.left + self.right


def subject(a, b, c):
    data = (a, b, a + b)
    box = PairBox(data[0], data[2])
    total = box.total()

    if c < 0:
        total = total - c
    else:
        total = total + c

    if total < 0:
        return 0 - total
    return total
