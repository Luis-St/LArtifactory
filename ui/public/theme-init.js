// Applies the stored theme before the first paint, an inline script would be blocked by the content security policy
(function () {
	var preference = 'system';
	try {
		preference = localStorage.getItem('lartifactory.theme') || 'system';
	} catch (e) {
		// Storage unavailable, the system preference is used
	}
	var dark = preference === 'dark' || (preference !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
	document.documentElement.dataset.theme = dark ? 'dark' : 'light';
})();
