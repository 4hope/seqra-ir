class ContainerState:
    def __init__(self, first, second):
        self.first = first
        self.second = second
        self.total = 0


def make_seed(a, b):
    data = {"x": a, "y": b}
    return data["x"] + data["y"]


def grow_list(a, b):
    data = [a, b, a + b]
    data[0] = data[1] + data[2]
    return data[0]


def shift_value(a, b):
    return ((a << 2) ^ (b >> 1)) & 255


def branch_box(a):
    if a < 0:
        return 0 - a
    return a


def dict_total(a, b, c):
    data = {"x": a, "y": b, "z": c}
    data["x"] = data["x"] + data["y"]
    data["z"] = data["z"] - data["y"]
    return data["x"] + data["z"]


def list_total(a, b, c):
    data = [a, b, c]
    data[1] = data[0] + data[2]
    data[2] = data[1] - data[0]
    return data[2]


def select_total(x):
    if x < 0:
        return 0 - x
    return x + 5


def select_mask(x):
    if x < 100:
        return x & 63
    return x & 31


def carry_one(x):
    return x + 1


def carry_two(x):
    return x + 2


def carry_three(x):
    return x + 3


def carry_four(x):
    return x + 4


def carry_five(x):
    return x + 5


def carry_six(x):
    return x + 6


def carry_seven(x):
    return x + 7


def carry_eight(x):
    return x + 8


def carry_nine(x):
    return x + 9


def carry_ten(x):
    return x + 10


def carry_eleven(x):
    return x + 11


def carry_twelve(x):
    return x + 12


def subject(a, b, c):
    state = ContainerState(a, b)
    data = {"left": a, "right": b}
    items = [a, b, a + b]
    items[0] = items[1] + items[2]
    items[2] = items[0] - a
    data["left"] = data["left"] + items[2]
    if c < 0:
        data["right"] = data["right"] - c
    else:
        data["right"] = data["right"] + c
    limit = (branch_box(c) & 3) + 1
    total = 0
    i = 0
    while i < limit:
        total = total + data["left"]
        if data["right"] < 0:
            total = total - 1
        else:
            total = total + 1
        i = i + 1
    state.total = total + data["right"]
    if state.total < 0:
        state.total = 0 - state.total
    return state.total
