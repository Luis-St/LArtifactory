import { useCallback, useEffect, useRef, useState } from 'react';

export interface Async<T> {
	data: T | undefined;
	error: Error | undefined;
	loading: boolean;
	reload: () => void;
}

/**
 * Loads data with the given loader and reloads it whenever one of the dependencies changes.
 */
export function useAsync<T>(loader: () => Promise<T>, deps: unknown[]): Async<T> {
	const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true });
	const [counter, setCounter] = useState(0);
	const loaderRef = useRef(loader);
	loaderRef.current = loader;

	useEffect(() => {
		let cancelled = false;
		setState(previous => ({ ...previous, loading: true }));
		loaderRef.current().then(
			data => !cancelled && setState({ data, loading: false }),
			error => !cancelled && setState({ error: error instanceof Error ? error : new Error(String(error)), loading: false }),
		);
		return () => {
			cancelled = true;
		};
	}, [...deps, counter]);

	const reload = useCallback(() => setCounter(value => value + 1), []);
	return { data: state.data, error: state.error, loading: state.loading, reload };
}
