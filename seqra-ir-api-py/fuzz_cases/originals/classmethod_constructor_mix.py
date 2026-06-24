import builtins


class Factory:
    def __init__(self, value: int):
        self.value = value

    def apply(self, extra: int) -> int:
        if extra < 0:
            self.value = self.value - extra
        else:
            self.value = self.value + extra
        return self.value


def subject(a: int, b: int) -> int:
    item = Factory(a + 2)
    total = item.apply(b)
    if total < 0:
        return builtins.abs(total) + 2
    return total - 2
