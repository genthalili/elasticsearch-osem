package com.github.kzwang.osem.processor;

import com.github.kzwang.osem.annotations.*;
import com.github.kzwang.osem.exception.ElasticSearchOsemException;
import com.github.kzwang.osem.utils.OsemReflectionUtils;
import com.google.common.base.CaseFormat;
import org.elasticsearch.common.Preconditions;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.reflections.ReflectionUtils.*;


public class MappingProcessor {

    private static final ESLogger logger = Loggers.getLogger(MappingProcessor.class);

    public static Map<String, Object> getMapping(Class clazz) {
        String indexableName = getIndexableName(clazz);

        Map<String, Object> mapping = Maps.newHashMap();
        Map<String, Object> objectMap = getMapping(clazz, null);


        // process root object
        Indexable indexable = (Indexable) clazz.getAnnotation(Indexable.class);
        Preconditions.checkNotNull(indexable, "Class {} is not Indexable", clazz.getName());

        if (indexable.parentClass() != void.class) {
            Map<String, Object> parentMap = Maps.newHashMap();
            parentMap.put("type", getIndexableName(indexable.parentClass()));
            objectMap.put("_parent", parentMap);
        }

        if (!indexable.indexAnalyzer().isEmpty()) {
            objectMap.put("index_analyzer", indexable.indexAnalyzer());
        }

        if (!indexable.searchAnalyzer().isEmpty()) {
            objectMap.put("search_analyzer", indexable.searchAnalyzer());
        }

        if (indexable.dynamicDateFormats().length > 0) {
            objectMap.put("dynamic_date_formats", Lists.newArrayList(indexable.dynamicDateFormats()));
        }

        if (!indexable.dateDetection().equals(DateDetectionEnum.NA)) {
            objectMap.put("date_detection", Boolean.valueOf(indexable.dateDetection().toString()));
        }

        if (!indexable.numericDetection().equals(NumericDetectionEnum.NA)) {
            objectMap.put("numeric_detection", Boolean.valueOf(indexable.numericDetection().toString()));
        }

        // handle IndexableId field
        Field indexableIdField = OsemReflectionUtils.getIdField(clazz);
        Map<String, Object> idMap = getIndexableIdMap(indexableIdField);
        if (!idMap.isEmpty()) {
            objectMap.put("_id", idMap);
        }

        mapping.put(indexableName, objectMap);

        return mapping;

    }

    public static String getMappingAsJson(Class clazz) {
        Map<String, Object> mappingMap = MappingProcessor.getMapping(clazz);
        if (mappingMap != null) {
            try {
                XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
                builder.map(mappingMap);
                return builder.string();
            } catch (IOException e) {
                logger.error("Failed to convert mapping to JSON string", e);
            }
        }
        return null;
    }

    public static String getIndexableName(Class clazz) {
        String typeName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, clazz.getSimpleName());
        Indexable indexable = (Indexable) clazz.getAnnotation(Indexable.class);
        if (indexable != null && indexable.name() != null && !indexable.name().isEmpty()) {
            typeName = indexable.name();
        }
        return typeName;
    }

    private static Map<String, Object> getMapping(Class clazz, Class fromClass) {
        Map<String, Object> propertiesMap = Maps.newHashMap();

        // process IndexableProperty
        Set<Field> indexablePropertyFields = getAllFields(clazz, withAnnotation(IndexableProperty.class));
        Set<Method> indexablePropertyMethods = getAllMethods(clazz, withAnnotation(IndexableProperty.class));
        if (!indexablePropertyFields.isEmpty()) {
            for (Field field : indexablePropertyFields) {
                processIndexableProperty(field, propertiesMap);
            }
        }
        if (!indexablePropertyMethods.isEmpty()) {
            for (Method method : indexablePropertyMethods) {
                processIndexableProperty(method, propertiesMap);
            }
        }


        // process IndexableComponent
        Set<Field> indexableComponentFields = getAllFields(clazz, withAnnotation(IndexableComponent.class));
        Set<Method> indexableComponentMethods = getAllMethods(clazz, withAnnotation(IndexableComponent.class));
        if (!indexableComponentFields.isEmpty()) {
            for (Field field : indexableComponentFields) {
                processIndexableComponent(field, propertiesMap, clazz, fromClass);
            }
        }
        if (!indexableComponentMethods.isEmpty()) {
            for (Method method : indexableComponentMethods) {
                processIndexableComponent(method, propertiesMap, clazz, fromClass);
            }
        }

        // process IndexableProperties
        Set<Field> indexablePropertiesFields = getAllFields(clazz, withAnnotation(IndexableProperties.class));
        Set<Method> indexablePropertiesMethods = getAllMethods(clazz, withAnnotation(IndexableProperties.class));
        if (!indexablePropertiesFields.isEmpty()) {
            for (Field field : indexablePropertiesFields) {
                processIndexableProperties(field, propertiesMap);
            }
        }
        if (!indexablePropertiesMethods.isEmpty()) {
            for (Method method : indexablePropertiesMethods) {
                processIndexableProperties(method, propertiesMap);
            }
        }

        Map<String, Object> indexNameMap = Maps.newHashMap();
        indexNameMap.put("properties", propertiesMap);
        return indexNameMap;

    }

    private static Map<String, Object> getIndexableIdMap(Field field) {
        Map<String, Object> idMap = Maps.newHashMap();

        IndexableId indexableId = field.getAnnotation(IndexableId.class);
        if (indexableId.index() != IndexEnum.NA) {
            idMap.put("index", indexableId.index().toString().toLowerCase());
        }

        if (indexableId.store()) {
            idMap.put("store", "yes");
        }

        IndexableProperty indexableProperty = field.getAnnotation(IndexableProperty.class);
        if (indexableProperty != null) {
            String fieldName = field.getName();
            if (indexableProperty.name() != null && !indexableProperty.name().isEmpty()) {
                fieldName = indexableProperty.name();
            }
            idMap.put("path", fieldName);  // only need to put this if the IndexableId field is also IndexableProperty
        }

        return idMap;
    }

    private static void processIndexableProperty(AccessibleObject accessibleObject, Map<String, Object> propertiesMap) {
        IndexableProperty indexableProperty = accessibleObject.getAnnotation(IndexableProperty.class);
        Preconditions.checkNotNull(indexableProperty, "Unable to find annotation IndexableProperty");
        String fieldName = null;
        if (accessibleObject instanceof Field) {
            fieldName = ((Field) accessibleObject).getName();
        }
        if (indexableProperty.name() != null && !indexableProperty.name().isEmpty()) {
            fieldName = indexableProperty.name();
        }

        Preconditions.checkNotNull(fieldName, "Unable to find field name");

        Map<String, Object> fieldMap = getIndexablePropertyMapping(accessibleObject, indexableProperty);
        if (fieldMap != null) {
            propertiesMap.put(fieldName, fieldMap);
        }
    }

    private static Map<String, Object> getIndexablePropertyMapping(AccessibleObject accessibleObject, IndexableProperty indexableProperty) {
        if (!indexableProperty.rawMapping().isEmpty()) {    // has raw mapping, use it directly
            return XContentHelper.convertToMap(indexableProperty.rawMapping().getBytes(), false).v2();
        }

        Map<String, Object> fieldMap = Maps.newHashMap();

        String fieldType = getFieldType(indexableProperty.type(), accessibleObject);

        if (fieldType.equals(TypeEnum.JSON.toString().toLowerCase())) {
            logger.warn("Can't find mapping for json, please specify rawMapping if needed");
            return null;
        }

        fieldMap.put("type", fieldType);

        if (indexableProperty.index() != IndexEnum.NA) {
            fieldMap.put("index", indexableProperty.index().toString().toLowerCase());
        }

        if (!indexableProperty.indexName().isEmpty()) {
            fieldMap.put("index_name", indexableProperty.indexName());
        }

        if (indexableProperty.termVector() != TermVectorEnum.NA) {
            fieldMap.put("term_vector", indexableProperty.termVector().toString().toLowerCase());
        }

        if (indexableProperty.store()) {
            fieldMap.put("store", "yes");
        }

        if (indexableProperty.boost() != 1.0) {
            fieldMap.put("boost", indexableProperty.boost());
        }

        if (!indexableProperty.nullValue().isEmpty()) {
            fieldMap.put("null_value", indexableProperty.nullValue());
        }

        if (indexableProperty.normsEnabled() != NormsEnabledEnum.NA) {
            fieldMap.put("norms.enabled", indexableProperty.normsEnabled().toString().toLowerCase());
        }

        if (indexableProperty.normsLoading() != NormsLoadingEnum.NA) {
            fieldMap.put("norms.loading", indexableProperty.normsLoading().toString().toLowerCase());
        }

        if (indexableProperty.indexOptions() != IndexOptionsEnum.NA) {
            fieldMap.put("index_options", indexableProperty.indexOptions().toString().toLowerCase());
        }

        if (!indexableProperty.analyzer().isEmpty()) {
            fieldMap.put("analyzer", indexableProperty.analyzer());
        }

        if (!indexableProperty.indexAnalyzer().isEmpty()) {
            fieldMap.put("index_analyzer", indexableProperty.indexAnalyzer());
        }

        if (!indexableProperty.searchAnalyzer().isEmpty()) {
            fieldMap.put("search_analyzer", indexableProperty.searchAnalyzer());
        }

        if (indexableProperty.includeInAll() != IncludeInAllEnum.NA) {
            fieldMap.put("include_in_all", indexableProperty.includeInAll().toString().toLowerCase());
        }

        if (indexableProperty.ignoreAbove() != Integer.MIN_VALUE) {
            fieldMap.put("ignore_above", indexableProperty.ignoreAbove());
        }

        if (indexableProperty.positionOffsetGap() != Integer.MIN_VALUE) {
            fieldMap.put("position_offset_gap", indexableProperty.positionOffsetGap());
        }

        if (indexableProperty.precisionStep() != Integer.MIN_VALUE) {
            fieldMap.put("precision_step", indexableProperty.precisionStep());
        }

        if (indexableProperty.ignoreMalformed()) {
            fieldMap.put("ignore_malformed", Boolean.TRUE.toString());
        }

        if (indexableProperty.postingsFormat() != PostingsFormatEnum.NA) {
            fieldMap.put("postings_format", indexableProperty.postingsFormat().toString().toLowerCase());
        }

        if (indexableProperty.similarity() != SimilarityEnum.NA) {
            switch (indexableProperty.similarity()) {
                case DEFAULT:
                    fieldMap.put("similarity", indexableProperty.postingsFormat().toString().toLowerCase());
                    break;
                case BM25: // BM25 should be uppercase
                    fieldMap.put("similarity", indexableProperty.postingsFormat().toString().toUpperCase());
                    break;
            }
        }


        if (!indexableProperty.format().isEmpty()) {
            fieldMap.put("format", indexableProperty.format());
        }

        if (indexableProperty.geoPointLatLon()) {
            fieldMap.put("lat_lon", Boolean.TRUE.toString());
        }

        if (indexableProperty.geoPointGeoHash()) {
            fieldMap.put("geohash", Boolean.TRUE.toString());
        }

        if (indexableProperty.geoPointGeoHashPrecision() != Integer.MIN_VALUE) {
            fieldMap.put("geohash_precision", indexableProperty.geoPointGeoHashPrecision());
        }

        if (indexableProperty.geoPointValidate()) {
            fieldMap.put("validate", Boolean.TRUE.toString());
        }

        if (indexableProperty.geoPointValidateLat()) {
            fieldMap.put("validate_lat", Boolean.TRUE.toString());
        }

        if (indexableProperty.geoPointValidateLon()) {
            fieldMap.put("validate_lon", Boolean.TRUE.toString());
        }

        if (!indexableProperty.geoPointNormalize()) {
            fieldMap.put("normalize", Boolean.FALSE.toString());
        }

        if (!indexableProperty.geoPointNormalizeLat()) {
            fieldMap.put("normalize_lat", Boolean.FALSE.toString());
        }

        if (!indexableProperty.geoPointNormalizeLon()) {
            fieldMap.put("normalize_lon", Boolean.FALSE.toString());
        }

        if (indexableProperty.geoShapeTree() != GeoShapeTreeEnum.NA) {
            fieldMap.put("tree", indexableProperty.geoShapeTree().toString().toLowerCase());
        }

        if (!indexableProperty.geoShapePrecision().isEmpty()) {
            fieldMap.put("precision", indexableProperty.geoShapePrecision());
        }

        if (indexableProperty.geoShapeDistanceErrorPct() != Float.MIN_VALUE) {
            fieldMap.put("distance_error_pct", indexableProperty.geoShapeDistanceErrorPct());
        }


        return fieldMap;

    }

    private static void processIndexableComponent(AccessibleObject accessibleObject, Map<String, Object> propertiesMap, Class clazz, Class fromClass) {
        IndexableComponent indexableComponent = accessibleObject.getAnnotation(IndexableComponent.class);
        Preconditions.checkNotNull(indexableComponent, "Unable to find annotation IndexableComponent");
        String fieldName = null;
        if (accessibleObject instanceof Field) {
            fieldName = ((Field) accessibleObject).getName();
        }
        if (indexableComponent.name() != null && !indexableComponent.name().isEmpty()) {
            fieldName = indexableComponent.name();
        }

        Preconditions.checkNotNull(fieldName, "Unable to find field name");

        Map<String, Object> fieldMap = getIndexableComponentMapping(accessibleObject, indexableComponent, clazz, fromClass);
        if (fieldMap != null) {
            propertiesMap.put(fieldName, fieldMap);
        }
    }

    private static Map<String, Object> getIndexableComponentMapping(AccessibleObject accessibleObject, IndexableComponent indexableComponent, Class clazz, Class fromClass) {
        Class fieldClazz = null;
        if (accessibleObject instanceof Field) {
            fieldClazz = OsemReflectionUtils.getGenericType((Field) accessibleObject);
        } else if (accessibleObject instanceof Method) {
            fieldClazz = OsemReflectionUtils.getGenericType((Method) accessibleObject);
        }
        Preconditions.checkNotNull(fieldClazz, "Unknown AccessibleObject type");

        if (fromClass != null && fieldClazz == fromClass) {
            return null;
        }

        Map<String, Object> fieldMap = getMapping(fieldClazz, clazz);
        if (indexableComponent.nested()) {
            fieldMap.put("type", "nested");
        } else {
            fieldMap.put("type", "object");
        }

        if (indexableComponent.dynamic() != DynamicEnum.NA) {
            fieldMap.put("dynamic", indexableComponent.dynamic().toString().toLowerCase());
        }

        if (!indexableComponent.enabled()) {
            fieldMap.put("enabled", Boolean.FALSE.toString());
        }

        if (!indexableComponent.path().isEmpty()) {
            fieldMap.put("path", indexableComponent.path());
        }

        if (indexableComponent.includeInAll() != IncludeInAllEnum.NA) {
            fieldMap.put("include_in_all", indexableComponent.includeInAll().toString().toLowerCase());
        }

        return fieldMap;
    }


    private static void processIndexableProperties(AccessibleObject accessibleObject, Map<String, Object> propertiesMap) {
        IndexableProperties indexableProperties = accessibleObject.getAnnotation(IndexableProperties.class);
        Preconditions.checkNotNull(indexableProperties, "Unable to find annotation IndexableProperties");
        Preconditions.checkArgument(indexableProperties.properties().length > 0, "IndexableProperties must have at lease one IndexableProperty");

        String fieldName = null;
        if (accessibleObject instanceof Field) {
            fieldName = ((Field) accessibleObject).getName();
        }
        if (indexableProperties.name() != null && !indexableProperties.name().isEmpty()) {
            fieldName = indexableProperties.name();
        }

        Map<String, Object> multiFieldMap = Maps.newHashMap();
        multiFieldMap.put("type", "multi_field");

        if (indexableProperties.path() != MultiFieldPathEnum.NA) {
            multiFieldMap.put("path", indexableProperties.path().toString().toLowerCase());
        }

        Map<String, Object> fieldsMap = Maps.newHashMap();
        for (IndexableProperty property : indexableProperties.properties()) {
            String propertyName = property.name();
            if (propertyName == null || propertyName.isEmpty()) {
                throw new ElasticSearchOsemException("Field name cannot be empty in multi-field");
            }
            Map<String, Object> fieldMap = getIndexablePropertyMapping(accessibleObject, property);
            fieldsMap.put(propertyName, fieldMap);
        }
        multiFieldMap.put("fields", fieldsMap);
        propertiesMap.put(fieldName, multiFieldMap);
    }

    private static String getFieldType(TypeEnum fieldTypeEnum, AccessibleObject accessibleObject) {
        String fieldType = "";

        if (fieldTypeEnum.equals(TypeEnum.AUTO)) {
            Class fieldClass = null;
            if (accessibleObject instanceof Field) {
                fieldClass = OsemReflectionUtils.getGenericType((Field) accessibleObject);
            } else if (accessibleObject instanceof Method) {
                fieldClass = OsemReflectionUtils.getGenericType((Method) accessibleObject);
            }
            Preconditions.checkNotNull(fieldClass, "Unknown AccessibleObject type");
            fieldType = fieldClass.getSimpleName().toLowerCase();
        } else {
            fieldType = fieldTypeEnum.toString().toLowerCase();
        }
        return fieldType;
    }


}
