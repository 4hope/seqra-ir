class Box:
    def __init__(self, value, step):
        self.value = value
        self.step = step
        self.count = 0


class Pair:
    def __init__(self, left, right):
        self.left = left
        self.right = right


def base_shift(a, b):
    return ((a << 1) ^ (b >> 1)) & 255


def base_floor(a, b):
    if b == 0:
        return 0
    return a // b


def base_mod(a, b):
    if b == 0:
        return 0
    return a % b


def small_guard(x):
    if x < 0:
        return 0 - x
    return x


def bigger_guard(x):
    if x < 10:
        return x + 2
    return x - 2


def dict_piece(a, b):
    data = {"x": a, "y": b}
    data["x"] = data["x"] + data["y"]
    return data["x"]


def list_piece(a, b):
    data = [a, b, a + b]
    data[1] = data[0] + data[2]
    return data[1]


def alt_one(x):
    return x + 1


def alt_two(x):
    return x + 2


def alt_three(x):
    return x + 3


def alt_four(x):
    return x + 4


def alt_five(x):
    return x + 5


def alt_six(x):
    return x + 6


def alt_seven(x):
    return x + 7


def alt_eight(x):
    return x + 8


def alt_nine(x):
    return x + 9


def alt_ten(x):
    return x + 10


def alt_eleven(x):
    return x + 11


def alt_twelve(x):
    return x + 12


def alt_thirteen(x):
    return x + 13


def subject(a, b, c):
    pair = Pair(a, b)
    if c == 0:
        seed = base_shift(pair.left, pair.right)
    else:
        seed = base_floor(a, c) - base_mod(a, c)
    box = Box(seed, small_guard(c) + 1)
    limit = (box.step & 3) + 2
    total = 0
    while box.count < limit:
        if pair.left < pair.right:
            total = total + pair.left
        else:
            total = total + pair.right
        if box.value < 0:
            total = total - 1
        else:
            total = total + 1
        box.value = box.value + box.step
        box.count = box.count + 1
    box.value = total + box.value
    if box.value < 0:
        box.value = 0 - box.value
    return box.value
