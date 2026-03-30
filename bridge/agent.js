import Java from "frida-java-bridge";

rpc.exports = {
    listclasses: function(searchParam) {
        var classes = [];
        var lowercaseSearch = searchParam ? searchParam.toLowerCase() : "";
        Java.perform(function() {
            Java.enumerateLoadedClasses({
                onMatch: function(className) {
                    if (!lowercaseSearch || className.toLowerCase().includes(lowercaseSearch)) {
                        classes.push(className);
                    }
                },
                onComplete: function() {}
            });
        });
        return classes;
    }
};
