import builtins


class Tools:
    @staticmethod
    def adjust(value: int) -> int:
        return builtins.abs(value) + 1


def subject(x: int) -> int:
    return Tools.adjust(x)
