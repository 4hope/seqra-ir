class Base:
    def ping(self) -> int:
        return 1


class Child(Base):
    stored: int

    def __init__(self) -> None:
        self.stored = 1

    @property
    def data(self) -> int:
        return self.stored

    @data.setter
    def data(self, value: int) -> None:
        self.stored = value
