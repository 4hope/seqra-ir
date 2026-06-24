class NumericState:
    def __init__(self, left, right, total):
        self.left = left
        self.right = right
        self.total = total


def normalize_low(x):
    if x < 0:
        return 0 - x
    return x


def normalize_high(x):
    if x < 0:
        return 0 - x
    return x + 1


def bit_mix_one(a, b):
    return ((a << 1) ^ (b >> 1)) & 255


def bit_mix_two(a, b):
    return ((a << 2) ^ (b >> 2)) & 255


def branch_add(a, b):
    if a < b:
        return a + b
    return a - b


def branch_sub(a, b):
    if a < 0:
        return b - a
    return a - b


def floor_piece(a, b):
    if b == 0:
        return 0
    return a // b


def mod_piece(a, b):
    if b == 0:
        return 0
    return a % b


def loop_sum(seed, step, count):
    total = seed
    i = 0
    while i < count:
        total = total + step
        i = i + 1
    return total


def loop_drop(seed, count):
    total = seed
    left = count
    while left > 0:
        total = total - 1
        left = left - 1
    return total


def guard_one(x):
    if x < 10:
        return x + 3
    return x - 3


def guard_two(x):
    if x < 20:
        return x + 2
    return x - 2


def guard_three(x):
    if x < 30:
        return x + 1
    return x - 1


def guard_four(x):
    if x < 40:
        return x + 4
    return x - 4


def subject(a, b, c):
    state = NumericState(a, b, 0)
    mixed = state.left << 1
    shifted = state.right >> 2
    mixed = mixed ^ shifted
    mixed = mixed & 255
    if c == 0:
        step = mixed
    else:
        step = (a // c) - (a % c)
    count = normalize_low(c)
    count = count & 3
    count = count + 2
    total = 0
    i = 0
    while i < count:
        if step < 0:
            total = total - mixed
        else:
            total = total + mixed
        if b < 0:
            total = total - 1
        else:
            total = total + 1
        i = i + 1
    state.total = total + step
    if state.total < 0:
        state.total = 0 - state.total
    return state.total
