import { useEffect, useState } from 'react';

/**
 * Minimal hash based router, the ui is served as static files below /ui/ so the server never sees client side routes.
 */
export interface Route {
	segments: string[];
	params: URLSearchParams;
}

function parse(): Route {
	const hash = window.location.hash.replace(/^#\/?/, '');
	const [path, search = ''] = hash.split('?', 2);
	return {
		segments: path.split('/').filter(Boolean).map(decodeURIComponent),
		params: new URLSearchParams(search),
	};
}

export function useRoute(): Route {
	const [route, setRoute] = useState(parse);
	useEffect(() => {
		const listener = () => setRoute(parse());
		window.addEventListener('hashchange', listener);
		return () => window.removeEventListener('hashchange', listener);
	}, []);
	return route;
}

export function href(...segments: string[]): string {
	return '#/' + segments.map(encodeURIComponent).join('/');
}

export function navigate(target: string): void {
	window.location.hash = target.startsWith('#') ? target.substring(1) : target;
}
