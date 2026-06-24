class Box:
    def __init__(self, value):
        self.value = value


def subject(x):
    box = Box(x + 1)
    return box.value
