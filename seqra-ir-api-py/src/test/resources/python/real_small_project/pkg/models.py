class Counter:
    def __init__(self, value: int):
        self.value = value
        self.offset = 1

    def bump(self, delta: int) -> int:
        self.value = self.value + delta
        return self.value + self.offset
