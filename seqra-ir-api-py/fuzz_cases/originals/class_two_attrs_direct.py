class Pair:
    def __init__(self, left, right):
        self.left = left
        self.right = right


def subject(a, b):
    pair = Pair(a, b)
    return pair.left + pair.right
