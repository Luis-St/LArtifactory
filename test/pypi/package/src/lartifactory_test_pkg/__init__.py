from importlib.metadata import version

__version__ = version("LArtifactory_Test.Pkg")


def hello() -> str:
	return f"hello from lartifactory-test-pkg {__version__}"
