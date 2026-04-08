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
            // 1. Handle Enum
            try {
                if (Java.use("java.lang.Enum").class.isInstance(obj)) {
                    return obj.name();
                }
            } catch(e) {}

            // 2. Handle Collection sizes
            try {
                if (Java.use("java.util.Collection").class.isInstance(obj)) {
                    return obj.getClass().getSimpleName() + "(size=" + obj.size() + ")";
                }
                if (Java.use("java.util.Map").class.isInstance(obj)) {
                    return obj.getClass().getSimpleName() + "(size=" + obj.size() + ")";
                }
            } catch(e) {}

            // 3. Handle Arrays
            if (obj.getClass().isArray()) {
                return obj.getClass().getSimpleName() + "[" + Java.use("java.lang.reflect.Array").getLength(obj) + "]";
            }

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

function getInstanceStatus(instance) {
    var status = "active";
    try {
        Java.perform(function() {
            var className = instance.getClass().getName();
            // Check Activity
            if (className.indexOf("Activity") !== -1 || className.indexOf("Fragment") !== -1) {
                try {
                    if (instance.isDestroyed()) status = "destroyed";
                    else if (instance.isFinishing()) status = "finishing";
                } catch(e) {}
            }
            // Check View
            if (className.indexOf("View") !== -1) {
                try {
                    if (!instance.isAttachedToWindow()) status = "detached";
                } catch(e) {}
            }
        });
    } catch(e) {}
    return status;
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
        var staticAttributes = [];
        var instanceAttributes = [];
        var methods = [];
        try {
            Java.perform(function() {
                var clazz = Java.use(className);
                var classDef = clazz.class;
                
                var Modifier = Java.use("java.lang.reflect.Modifier");
                var fields = classDef.getDeclaredFields();
                for (var i = 0; i < fields.length; i++) {
                    var f = fields[i];
                    if (Modifier.isStatic(f.getModifiers())) {
                        staticAttributes.push(f.toString());
                    } else {
                        instanceAttributes.push(f.toString());
                    }
                }
                
                var funcs = classDef.getDeclaredMethods();
                for (var j = 0; j < funcs.length; j++) {
                    methods.push(funcs[j].toString());
                }
            });
            return { staticAttributes: staticAttributes, instanceAttributes: instanceAttributes, methods: methods };
        } catch (e) {
            // Using error field to communicate exceptions back to RPC
            return { error: e.toString(), staticAttributes: [], instanceAttributes: [], methods: [] };
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
                            if (instances.length < 1000) {
                                var hndl = instance.$handle ? instance.$handle.toString() : "";
                                instanceCache[id] = instance;
                                instances.push({
                                    id: id,
                                    handle: hndl,
                                    summary: instance.toString() + " (" + getInstanceStatus(instance) + ")"
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

    inspectinstance: function(className, id, offset, limit) {
        var attributes = [];
        offset = offset || 0;
        limit = limit || 50;
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
                
                // 1. Detect if it's a Map, Collection, or Array
                var isMap = false;
                var isCollection = false;
                var isArray = actualClassName.startsWith('[');
                
                try {
                    var Map = Java.use("java.util.Map");
                    var Collection = Java.use("java.util.Collection");
                    if (Map.class.isInstance(instance)) isMap = true;
                    if (Collection.class.isInstance(instance)) isCollection = true;
                } catch(e) {}

                // 2. If it's a collection-like thing, show its items first
                if (isMap) {
                    var entrySet = Java.cast(instance, Java.use("java.util.Map")).entrySet().iterator();
                    var skipped = 0;
                    while (entrySet.hasNext() && skipped < offset) {
                        entrySet.next();
                        skipped++;
                    }
                    var count = 0;
                    while (entrySet.hasNext() && count < limit) {
                        var entry = Java.cast(entrySet.next(), Java.use("java.util.Map$Entry"));
                        var key = entry.getKey();
                        var val = entry.getValue();
                        var keyStr = key !== null ? key.toString() : "null";
                        var valStr = val !== null ? javaToString(val) : "null";
                        
                        var childId = null;
                        var childClassName = null;
                        if (val !== null && typeof val === 'object') {
                            childId = val.hashCode().toString();
                            instanceCache[childId] = val;
                            try { childClassName = val.getClass().getName(); } catch(e) {}
                        }
                        
                        attributes.push({ 
                            name: keyStr, 
                            type: childClassName || "MapEntry", 
                            value: valStr, 
                            childId: childId, 
                            childClassName: childClassName,
                            isPagination: false
                        });
                        count++;
                    }
                    if (entrySet.hasNext()) {
                         attributes.push({ 
                             name: "...", 
                             type: "Action", 
                             value: "Show more items", 
                             childId: id, // Pass the same instance ID to expand more
                             isPagination: true,
                             nextOffset: offset + limit
                         });
                    }
                } else if (isCollection) {
                    var iterator = Java.cast(instance, Java.use("java.util.Collection")).iterator();
                    var skipped = 0;
                    while (iterator.hasNext() && skipped < offset) {
                        iterator.next();
                        skipped++;
                    }
                    var count = 0;
                    while (iterator.hasNext() && count < limit) {
                        var val = iterator.next();
                        var name = "[" + (offset + count) + "]";
                        var valStr = val !== null ? javaToString(val) : "null";
                        
                        var childId = null;
                        var childClassName = null;
                        if (val !== null && typeof val === 'object') {
                            childId = val.hashCode().toString();
                            instanceCache[childId] = val;
                            try { childClassName = val.getClass().getName(); } catch(e) {}
                        }
                        
                        attributes.push({ 
                            name: name, 
                            type: childClassName || "Element", 
                            value: valStr, 
                            childId: childId, 
                            childClassName: childClassName,
                            isPagination: false
                        });
                        count++;
                    }
                    if (iterator.hasNext()) {
                        attributes.push({ 
                            name: "...", 
                            type: "Action", 
                            value: "Show more items", 
                            childId: id, 
                            isPagination: true,
                            nextOffset: offset + limit
                        });
                    }
                } else if (isArray) {
                    var len = Java.use("java.lang.reflect.Array").getLength(instance);
                    var end = Math.min(len, offset + limit);
                    for (var i = offset; i < end; i++) {
                        var val = Java.use("java.lang.reflect.Array").get(instance, i);
                        var name = "[" + i + "]";
                        var valStr = val !== null ? javaToString(val) : "null";
                        
                        var childId = null;
                        var childClassName = null;
                        if (val !== null && typeof val === 'object') {
                            childId = val.hashCode().toString();
                            instanceCache[childId] = val;
                            try { childClassName = val.getClass().getName(); } catch(e) {}
                        }
                        
                        attributes.push({ 
                            name: name, 
                            type: childClassName || "Element", 
                            value: valStr, 
                            childId: childId, 
                            childClassName: childClassName,
                            isPagination: false
                        });
                    }
                    if (len > end) {
                         attributes.push({ 
                             name: "...", 
                             type: "Action", 
                             value: "Show more items", 
                             childId: id, 
                             isPagination: true,
                             nextOffset: offset + limit
                         });
                    }
                }

                // 3. Only show regular fields on the FIRST page (offset 0)
                if (offset === 0) {
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
                        attributes.push({ 
                            name: name, 
                            type: childClassName || type, 
                            value: valStr, 
                            childId: childId, 
                            childClassName: childClassName,
                            isPagination: false
                        });
                    }
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
                
                if (targetClass[methodName] && targetClass[methodName].overloads) {
                    if (activeHookImplementations[className + methodSig]) {
                        return;
                    }
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
                    // It's probably a field if it's not a method
                    if (monitoredFields[className + methodSig]) return;
                    
                    var isStaticField = false;
                    try {
                        var clazz = Java.use(className);
                        var currentClass = clazz.class;
                        var field = null;
                        var Modifier = Java.use("java.lang.reflect.Modifier");

                        // Iterate hierarchy to find the field and check if it's static
                        while (currentClass !== null) {
                            try {
                                field = currentClass.getDeclaredField(methodName);
                                break;
                            } catch (e) {
                                currentClass = currentClass.getSuperclass();
                            }
                        }

                        if (field !== null) {
                            field.setAccessible(true);
                            if (Modifier.isStatic(field.getModifiers())) {
                                isStaticField = true;
                            }
                        }
                    } catch (e) {
                        console.error("Error checking field " + methodName + " on " + className + ": " + e);
                    }

                    if (!isStaticField) {
                        console.warn("Field " + methodName + " is not static or not found. Instance field hooking is not supported yet.");
                        // Send an event to the UI so the user knows why it's not working
                        hookEvents.push({
                            timestamp: Date.now(),
                            target: { className: className, memberSignature: methodSig, type: "FIELD" },
                            data: { value: "[Error] Not a static field" }
                        });
                        return;
                    }

                    monitoredFields[className + methodSig] = {
                        className: className,
                        fieldName: methodName,
                        signature: methodSig,
                        lastValue: undefined
                    };

                    // Send an initial event to confirm hook is active
                    hookEvents.push({
                        timestamp: Date.now(),
                        target: { className: className, memberSignature: methodSig, type: "FIELD" },
                        data: { value: "Watching for changes..." }
                    });
                    
                    if (!fieldPollingInterval) {
                        fieldPollingInterval = setInterval(function() {
                            Java.perform(function() {
                                for (var key in monitoredFields) {
                                    var info = monitoredFields[key];
                                    try {
                                        var clazz = Java.use(info.className);
                                        var currentClass = clazz.class;
                                        var field = null;
                                        while (currentClass !== null) {
                                            try {
                                                field = currentClass.getDeclaredField(info.fieldName);
                                                break;
                                            } catch (e) {
                                                currentClass = currentClass.getSuperclass();
                                            }
                                        }
                                        if (field) {
                                            field.setAccessible(true);
                                            var val = field.get(null);
                                            var valStr = javaToString(val);
                                            
                                            if (info.lastValue === undefined) {
                                                info.lastValue = valStr;
                                            } else if (valStr !== info.lastValue) {
                                                var oldVal = info.lastValue;
                                                info.lastValue = valStr;
                                                hookEvents.push({
                                                    timestamp: Date.now(),
                                                    target: { className: info.className, memberSignature: info.signature, type: "FIELD" },
                                                    data: { value: oldVal + " | " + valStr }
                                                });
                                            }
                                        }
                                    } catch (e) {}
                                }
                            });
                        }, 250);
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
