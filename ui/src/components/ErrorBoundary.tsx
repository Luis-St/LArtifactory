import {Component, type ErrorInfo, type ReactNode} from 'react';

/**
 * Catches render errors of the page below it, so a failing page shows a message instead of leaving the whole window blank.
 * It resets when the key changes, the layout keys it by the route.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
	state: { error: Error | null } = {error: null};

	static getDerivedStateFromError(error: Error) {
		return {error};
	}

	componentDidCatch(error: Error, info: ErrorInfo) {
		console.error('Page failed to render', error, info.componentStack);
	}

	render() {
		if (this.state.error) {
			return (
				<div className="error-box" role="alert">
					<strong>This page could not be shown.</strong> {this.state.error.message}{' '}
					<a className="link" href="#/">Back to the overview</a>
				</div>
			);
		}
		return this.props.children;
	}
}
