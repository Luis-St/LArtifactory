const { version } = require("./package.json");

module.exports.hello = () => `hello from test-lib ${version}`;
