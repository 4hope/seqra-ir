class Flagged:
    def __init__(self, value):
        self.value = value

    def normalize(self):
        if self.value < 0:
            return 0 - self.value
        return self.value


def subject(x):
    item = Flagged(x)
    return item.normalize()
