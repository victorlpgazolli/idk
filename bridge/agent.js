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
    },
    
    inspectclass: function(className) {
        var attributes = [];
        var methods = [];
        try {
            Java.perform(function() {
                var clazz = Java.use(className);
                var classDef = clazz.class;
                
                var fields = classDef.getDeclaredFields();
                for (var i = 0; i < fields.length; i++) {
                    attributes.push(fields[i].toString());
                }
                
                var funcs = classDef.getDeclaredMethods();
                for (var j = 0; j < funcs.length; j++) {
                    methods.push(funcs[j].toString());
                }
            });
            return { attributes: attributes, methods: methods };
        } catch (e) {
            // Using error field to communicate exceptions back to RPC
            return { error: e.toString(), attributes: [], methods: [] };
        }
    }
};
