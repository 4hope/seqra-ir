class Cell:
    def __init__(self, value, step):
        self.value = value
        self.step = step
        self.count = 0


class Duo:
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


def guard(x):
    if x < 0:
        return 0 - x
    return x


def branch_seed(a, b):
    if a < b:
        return a + b
    return a - b


def branch_tail(a, b):
    if a < 0:
        return b - a
    return a - b


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
    duo = Duo(a, b)
    if c == 0:
        seed = base_shift(duo.left, duo.right)
    else:
        seed = base_floor(a, c) - base_mod(a, c)

    cell = Cell(seed, guard(c) + 1)
    limit = (cell.step & 3) + 2
    total = 0

    while cell.count < limit:
        if duo.left < duo.right:
            total = total + duo.left
        else:
            total = total + duo.right

        if cell.value < 0:
            total = total - 1
        else:
            total = total + 1

        cell.value = cell.value + cell.step
        cell.count = cell.count + 1

    cell.value = total + cell.value
    if cell.value < 0:
        cell.value = 0 - cell.value
    return cell.value
