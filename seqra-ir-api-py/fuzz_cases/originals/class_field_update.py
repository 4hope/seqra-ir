class Box:
    def __init__(self, value):
        self.value = value


def subject(a, b):
    box = Box(a)
    box.value = box.value + b
    return box.value
