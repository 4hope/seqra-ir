class Counter:
    def __init__(self, start):
        self.value = start

    def add(self, delta):
        self.value = self.value + delta
        return self.value


def subject(a, b):
    counter = Counter(a)
    return counter.add(b)
