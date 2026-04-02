export const MAX_USERS = 100;
let currentUser: string = "Alice";

const config = {
    host: "localhost",
    port: 8080
};

const anArrowFunc = (msg: string): void => {
    console.log(msg);
};

export var legacyVar = "legacy";

function localHelper(): string {
    return "helper";
}
