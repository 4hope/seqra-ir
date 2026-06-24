import pkg.pipeline as pkg_pipeline


def subject(a: int, b: int, c: int) -> int:
    return pkg_pipeline.process(a, b, c)
