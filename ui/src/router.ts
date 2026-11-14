import {useEffect, useState} from 'react';

/**
 * Minimal hash based router, the ui is served as static files below /ui/ so the server never sees client side routes.
 */
export interface Route {
	segments: string[];
	params: URLSearchParams;
}

/**
 * Decodes one path segment, a malformed escape like a lone % is kept as it is instead of crashing the page.
 */
function decodeSegment(segment: string): string {
	try {
		return decodeURIComponent(segment);
	} catch {
		return segment;
	}
}

function parse(): Route {
	const hash = window.location.hash.replace(/^#\/?/, '');
	const [path, search = ''] = hash.split('?', 2);
	return {
		segments: path.split('/').filter(Boolean).map(decodeSegment),
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

/**
 * The page of one version of a package.
 */
export function versionHref(repository: string, pkg: string, version: string): string {
	return href('repositories', repository, 'packages', pkg, 'versions', version);
}
