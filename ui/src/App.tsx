import {ErrorBoundary} from './components/ErrorBoundary';
import {Layout} from './components/Layout';
import {Button, Empty, ErrorBox} from './components/ui';
import {Account} from './pages/Account';
import {Artifacts} from './pages/Artifacts';
import {Audit} from './pages/Audit';
import {Login} from './pages/Login';
import {Overview} from './pages/Overview';
import {PackageDetail} from './pages/PackageDetail';
import {VersionDetail} from './pages/VersionDetail';
import {Permissions} from './pages/Permissions';
import {Repositories} from './pages/Repositories';
import {RepositoryDetail} from './pages/RepositoryDetail';
import {Tokens} from './pages/Tokens';
import {Users} from './pages/Users';
import {Webhooks} from './pages/Webhooks';
import {href, useRoute} from './router';
import {SessionProvider} from './session';
import {canAdminister, type Principal, REPOSITORY_TYPES, type RepositoryType} from './types';

export function App() {
	return (
		<SessionProvider>
			{({ready, principal, guest, unreachable, retry}) => (unreachable ? <Unreachable error={unreachable} onRetry={retry}/>
				: !ready ? <div className="login"/> : principal || guest ? <Routes principal={principal}/> : <Login/>)}
		</SessionProvider>
	);
}

/**
 * Shown while the stored session can not be checked, the server is restarting or unreachable. The session is kept and checked again on retry.
 */
function Unreachable({error, onRetry}: { error: Error; onRetry: () => void }) {
	return (
		<div className="login">
			<div className="stack">
				<ErrorBox error={`The server can not be reached right now (${error.message}). You are still signed in.`}/>
				<div><Button variant="primary" onClick={onRetry}>Try again</Button></div>
			</div>
		</div>
	);
}

function Routes({principal}: { principal: Principal | null }) {
	const admin = canAdminister(principal);
	const {segments, params} = useRoute();
	const [section = '', id, sub, subId, detail, detailId] = segments;
	const type = REPOSITORY_TYPES.includes(id as RepositoryType) ? (id as RepositoryType) : undefined;
	
	let page;
	switch (section) {
		case '':
			page = <Overview/>;
			break;
		case 'artifacts':
			page = <Artifacts type={type}/>;
			break;
		case 'repositories':
			page = id && sub === 'packages' && subId && detail === 'versions' && detailId ? <VersionDetail key={`${id}/${subId}/${detailId}`} repositoryName={id} packageName={subId} version={detailId}/>
				: id && sub === 'packages' && subId ? <PackageDetail key={`${id}/${subId}`} repositoryName={id} packageName={subId} params={params}/>
				: id ? <RepositoryDetail key={id} name={id} params={params}/> : <Repositories/>;
			break;
		case 'users':
			page = admin ? <Users/> : null;
			break;
		case 'permissions':
			page = admin ? <Permissions params={params}/> : null;
			break;
		case 'tokens':
			page = principal ? <Tokens params={params}/> : null;
			break;
		case 'webhooks':
			page = admin ? <Webhooks /> : null;
			break;
		case 'audit':
			page = admin ? <Audit /> : null;
			break;
		case 'account':
			page = principal ? <Account/> : null;
			break;
	}
	
	return (
		<Layout section={section} type={type}>
			{/* Keyed by the path so every page change fades in, query changes (tabs, filters) keep the page */}
			<div className="page" key={segments.join('/')}>
				<ErrorBoundary>
					{page ?? <Empty title="Page not found"><a className="link" href={href()}>Back to the overview</a></Empty>}
				</ErrorBoundary>
			</div>
		</Layout>
	);
}
