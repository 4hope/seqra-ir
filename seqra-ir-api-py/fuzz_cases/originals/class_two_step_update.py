class Node:
    def __init__(self, value):
        self.value = value

    def grow(self, delta):
        self.value = self.value + delta
        return self.value


def subject(a, b, c):
    node = Node(a)
    first = node.grow(b)
    second = node.grow(c)
    total = first + second
    if total < 0:
        return 0 - total
    return total + node.value
