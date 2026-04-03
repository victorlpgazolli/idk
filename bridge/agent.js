import Java from "frida-java-bridge";

var instanceCache = {};

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
    
    getpackagename: function() {
        var pkgName = "";
        try {
            Java.perform(function() {
                var ActivityThread = Java.use('android.app.ActivityThread');
                var app = ActivityThread.currentApplication();
                if (app != null) {
                    pkgName = app.getPackageName();
                }
            });
        } catch (e) {
            // graceful fallback
        }
        return pkgName;
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
    },

    countinstances: function(className) {
        var count = 0;
        var seen = {};
        try {
            Java.perform(function() {
                Java.choose(className, {
                    onMatch: function(instance) {
                        var id = instance.$handle ? instance.$handle.toString() : instance.hashCode().toString();
                        if (!seen[id]) {
                            seen[id] = true;
                            count++;
                        }
                    },
                    onComplete: function() {}
                });
            });
            return count;
        } catch (e) {
            return -1;
        }
    },

    listinstances: function(className) {
        var instances = [];
        var total = 0;
        var seen = {};
        try {
            Java.perform(function() {
                Java.choose(className, {
                    onMatch: function(instance) {
                        var id = instance.$handle ? instance.$handle.toString() : instance.hashCode().toString();
                        if (!seen[id]) {
                            seen[id] = true;
                            total++;
                            if (instances.length < 50) {
                                var hndl = instance.$handle ? instance.$handle.toString() : "";
                                instanceCache[id] = instance;
                                instances.push({
                                    id: id,
                                    handle: hndl,
                                    summary: instance.toString()
                                });
                            }
                        }
                    },
                    onComplete: function() {}
                });
            });
            return { instances: instances, totalCount: total };
        } catch (e) {
            return { error: e.toString(), instances: [], totalCount: 0 };
        }
    },

    inspectinstance: function(className, id) {
        var attributes = [];
        try {
            Java.perform(function() {
                var instance = instanceCache[id];
                if (!instance) {
                    throw new Error("Instance not found in cache.");
                }
                var clazz = Java.use(className);
                var classDef = clazz.class;
                
                var fields = classDef.getDeclaredFields();
                for (var i = 0; i < fields.length; i++) {
                    var field = fields[i];
                    field.setAccessible(true);
                    var name = field.getName();
                    var type = field.getType().getSimpleName();
                    var valStr = "unknown";
                    var childId = null;
                    var childClassName = null;
                    try {
                        var fieldVal = field.get(instance);
                        if (fieldVal !== null) {
                            valStr = fieldVal.toString();
                            var isBasicType = ["int", "long", "boolean", "byte", "short", "float", "double", "string", "charsequence", "char"].indexOf(type.toLowerCase()) !== -1;
                            if (!isBasicType) {
                                childId = fieldVal.hashCode().toString();
                                instanceCache[childId] = fieldVal;
                                try {
                                    childClassName = fieldVal.getClass().getName();
                                } catch(e) {
                                  childClassName = field.getType().getName();
                                }
                            }
                        } else {
                           valStr = "null";
                        }
                    } catch(fe) {
                        valStr = "error";
                    }
                    attributes.push({ name: name, type: type, value: valStr, childId: childId, childClassName: childClassName });
                }
            });
            return { attributes: attributes };
        } catch (e) {
            return { error: e.toString(), attributes: [] };
        }
    }
};
