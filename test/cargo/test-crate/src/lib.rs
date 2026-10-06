pub fn hello() -> String {
	if cfg!(feature = "extra") {
		String::from("hello from lartifactory-test-crate (extra)")
	} else {
		String::from("hello from lartifactory-test-crate")
	}
}
