// Applies the stored theme, accent and density before the first paint, an inline script would be blocked by the content security policy
(function () {
	var root = document.documentElement;
	var read = function (key, fallback) {
		try {
			return localStorage.getItem(key) || fallback;
		} catch (e) {
			// Storage unavailable, the default is used
			return fallback;
		}
	};
	var preference = read('lartifactory.theme', 'system');
	var dark = preference === 'dark' || (preference !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
	root.dataset.theme = dark ? 'dark' : 'light';
	root.dataset.accent = read('lartifactory.accent', 'indigo');
	root.dataset.density = read('lartifactory.density', 'comfortable');
})();
