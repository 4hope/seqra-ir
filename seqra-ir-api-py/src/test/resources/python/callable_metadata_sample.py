import math


class Tools:
    @staticmethod
    def twice(value: int) -> int:
        return value * 2

    @classmethod
    def identity(cls, value: int) -> int:
        return value


def outer(n: int) -> int:
    def inner(x: int) -> int:
        return x + 1

    local_lambda = lambda y: y + math.floor(0)
    return inner(n) + local_lambda(n)
