import {Layout} from './components/Layout';
import {Empty} from './components/ui';
import {Account} from './pages/Account';
import {Artifacts} from './pages/Artifacts';
import {Audit} from './pages/Audit';
import {Login} from './pages/Login';
import {Overview} from './pages/Overview';
import {PackageDetail} from './pages/PackageDetail';
import {Permissions} from './pages/Permissions';
import {Repositories} from './pages/Repositories';
import {RepositoryDetail} from './pages/RepositoryDetail';
import {Tokens} from './pages/Tokens';
import {Users} from './pages/Users';
import {Webhooks} from './pages/Webhooks';
import {href, useRoute} from './router';
import {SessionProvider} from './session';
import {type Principal, REPOSITORY_TYPES, type RepositoryType} from './types';

export function App() {
	return (
		<SessionProvider>
			{({ready, principal}) => (!ready ? <div className="login"/> : principal ? <Routes principal={principal}/> : <Login/>)}
		</SessionProvider>
	);
}

function Routes({principal}: { principal: Principal }) {
	const {segments, params} = useRoute();
	const [section = '', id, sub, subId] = segments;
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
			page = id && sub === 'packages' && subId ? <PackageDetail key={`${id}/${subId}`} repositoryName={id} packageName={subId} params={params}/>
				: id ? <RepositoryDetail key={id} name={id} params={params}/> : <Repositories/>;
			break;
		case 'users':
			page = principal.admin ? <Users/> : null;
			break;
		case 'permissions':
			page = principal.admin ? <Permissions params={params}/> : null;
			break;
		case 'tokens':
			page = <Tokens params={params}/>;
			break;
		case 'webhooks':
			page = principal.admin ? <Webhooks /> : null;
			break;
		case 'audit':
			page = principal.admin ? <Audit /> : null;
			break;
		case 'account':
			page = <Account/>;
			break;
	}
	
	return (
		<Layout section={section} type={type}>
			{page ?? <Empty title="Page not found"><a className="link" href={href()}>Back to the overview</a></Empty>}
		</Layout>
	);
}
