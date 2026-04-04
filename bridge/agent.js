import Java from "frida-java-bridge";

var instanceCache = {};
var hookEvents = [];
var activeHookImplementations = {};
var fieldPollingInterval = null;
var monitoredFields = {};

function javaToString(obj) {
    if (obj === null || obj === undefined) return "null";
    try {
        if (obj.$className) {
            // For Java wrappers, String.valueOf is the most robust way to get the Java toString() output
            return Java.use("java.lang.String").valueOf(obj);
        }
        if (typeof obj === 'object') {
            return JSON.stringify(obj);
        }
        return String(obj);
    } catch (e) {
        try {
            return obj.toString();
        } catch (e2) {
            return "[Object]";
        }
    }
}

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
                
                // Use the actual runtime class of the instance instead of the passed className
                var actualClassName = instance.getClass().getName();
                var clazz = Java.use(actualClassName);
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
                            valStr = javaToString(fieldVal);
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
    },

    hookmethod: function(className, methodSig) {
        Java.perform(function() {
            try {
                var targetClass = Java.use(className);
                var beforeArgs = methodSig.split('(')[0].trim();
                var parts = beforeArgs.split(' ');
                var fullMethodPath = parts[parts.length - 1];
                var dotParts = fullMethodPath.split('.');
                var methodName = dotParts[dotParts.length - 1];
                
                if (!targetClass[methodName]) {
                    throw new Error("Method '" + methodName + "' not found on class '" + className + "'");
                }
                
                if (activeHookImplementations[className + methodSig]) {
                    return;
                }
                
                if (targetClass[methodName].overloads) {
                    var overload = targetClass[methodName].overloads[0]; 

                    activeHookImplementations[className + methodSig] = overload.implementation;
                    overload.implementation = function() {
                        var args = {};
                        for (var i = 0; i < arguments.length; i++) {
                            args["arg" + i] = javaToString(arguments[i]);
                        }
                        var ret = overload.apply(this, arguments);
                        var retStr = (ret === undefined) ? "void" : javaToString(ret);

                        hookEvents.push({
                            timestamp: Date.now(),
                            target: { className: className, memberSignature: methodSig, type: "METHOD" },
                            data: { args: JSON.stringify(args), "return": retStr }
                        });
                        return ret;
                    };
                } else {
                    if (monitoredFields[className + methodSig]) return;
                    
                    monitoredFields[className + methodSig] = {
                        className: className,
                        fieldName: methodName,
                        signature: methodSig,
                        lastValue: undefined
                    };
                    
                    if (!fieldPollingInterval) {
                        fieldPollingInterval = setInterval(function() {
                            Java.perform(function() {
                                for (var key in monitoredFields) {
                                    var info = monitoredFields[key];
                                    try {
                                        var clazz = Java.use(info.className);
                                        var val = clazz[info.fieldName].value;
                                        var valStr = val !== null ? val.toString() : "null";
                                        if (valStr !== info.lastValue) {
                                            info.lastValue = valStr;
                                            hookEvents.push({
                                                timestamp: Date.now(),
                                                target: { className: info.className, memberSignature: info.signature, type: "FIELD" },
                                                data: { value: valStr }
                                            });
                                        }
                                    } catch (e) {}
                                }
                            });
                        }, 500);
                    }
                }
            } catch (e) {
                console.error("Hook failed: " + e);
            }
        });
        return true;
    },
    unhookmethod: function(className, methodSig) {
        Java.perform(function() {
            try {
                var targetClass = Java.use(className);
                var beforeArgs = methodSig.split('(')[0].trim();
                var parts = beforeArgs.split(' ');
                var fullMethodPath = parts[parts.length - 1];
                var dotParts = fullMethodPath.split('.');
                var methodName = dotParts[dotParts.length - 1];
                
                if (targetClass[methodName].overloads) {
                    if (activeHookImplementations.hasOwnProperty(className + methodSig)) {
                        targetClass[methodName].overloads[0].implementation = null;
                        delete activeHookImplementations[className + methodSig];
                    }
                } else {
                    delete monitoredFields[className + methodSig];
                    if (Object.keys(monitoredFields).length === 0 && fieldPollingInterval) {
                        clearInterval(fieldPollingInterval);
                        fieldPollingInterval = null;
                    }
                }
            } catch (e) {
                console.error("Unhook failed: " + e);
            }
        });
        return true;
    },
    gethookevents: function() {
        var events = hookEvents;
        hookEvents = [];
        return events;
    },

    setfieldvalue: function(className, id, fieldName, type, newValue) {
        try {
            return Java.perform(function() {
                var instance = instanceCache[id];
                if (!instance) {
                    throw new Error("Instance not found in cache.");
                }
                
                var actualClassName = instance.getClass().getName();
                var clazz = Java.use(actualClassName);
                var classDef = clazz.class;
                
                var field = classDef.getDeclaredField(fieldName);
                field.setAccessible(true);
                
                var t = type.toLowerCase();
                var val = null;
                if (t === "boolean" || t === "bool") val = Java.use("java.lang.Boolean").valueOf(newValue === "true");
                else if (t === "int") val = Java.use("java.lang.Integer").valueOf(parseInt(newValue, 10));
                else if (t === "long") val = Java.use("java.lang.Long").valueOf(parseInt(newValue, 10));
                else if (t === "float") val = Java.use("java.lang.Float").valueOf(parseFloat(newValue));
                else if (t === "double") val = Java.use("java.lang.Double").valueOf(parseFloat(newValue));
                else if (t === "short") val = Java.use("java.lang.Short").valueOf(parseInt(newValue, 10));
                else if (t === "byte") val = Java.use("java.lang.Byte").valueOf(parseInt(newValue, 10));
                else if (t === "char") val = Java.use("java.lang.Character").valueOf(newValue.charAt(0));
                else if (t === "string" || t === "charsequence") val = Java.use("java.lang.String").$new(newValue);
                else throw new Error("Unsupported type for editing");
                
                field.set(instance, val);
                return true;
            });
        } catch (e) {
            return { error: e.toString() };
        }
    }
};
