package cool.zhouxin.q_1010000023765242;

import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentProperty;
import org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentPropertyConverter;
import org.springframework.data.elasticsearch.core.query.SeqNoPrimaryTerm;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.model.AnnotationBasedPersistentProperty;
import org.springframework.data.mapping.model.Property;
import org.springframework.data.mapping.model.SimpleTypeHolder;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * @author zhouxin
 * @since 2020/10/15 18:25
 */
public class CustomSimpleElasticsearchPersistentProperty extends
        AnnotationBasedPersistentProperty<ElasticsearchPersistentProperty> implements ElasticsearchPersistentProperty {

    private static final List<String> SUPPORTED_ID_PROPERTY_NAMES = Arrays.asList("id", "document");

    private final boolean isScore;
    private final boolean isParent;
    private final boolean isId;
    private final boolean isSeqNoPrimaryTerm;
    private final @Nullable String annotatedFieldName;
    @Nullable private ElasticsearchPersistentPropertyConverter propertyConverter;

    public CustomSimpleElasticsearchPersistentProperty(Property property,
                                                 PersistentEntity<?, ElasticsearchPersistentProperty> owner, SimpleTypeHolder simpleTypeHolder) {

        super(property, owner, simpleTypeHolder);

        this.annotatedFieldName = getAnnotatedFieldName();
        this.isId = super.isIdProperty() || SUPPORTED_ID_PROPERTY_NAMES.contains(getFieldName());
        this.isScore = isAnnotationPresent(Score.class);
        this.isParent = isAnnotationPresent(Parent.class);
        this.isSeqNoPrimaryTerm = SeqNoPrimaryTerm.class.isAssignableFrom(getRawType());

        if (isVersionProperty() && !getType().equals(Long.class)) {
            throw new MappingException(String.format("Version property %s must be of type Long!", property.getName()));
        }

        if (isScore && !getType().equals(Float.TYPE) && !getType().equals(Float.class)) {
            throw new MappingException(
                    String.format("Score property %s must be either of type float or Float!", property.getName()));
        }

        if (isParent && !getType().equals(String.class)) {
            throw new MappingException(String.format("Parent property %s must be of type String!", property.getName()));
        }

        if (isAnnotationPresent(Field.class) && isAnnotationPresent(MultiField.class)) {
            throw new MappingException("@Field annotation must not be used on a @MultiField property.");
        }

        initDateConverter();
    }

    @Override
    public boolean hasPropertyConverter() {
        return propertyConverter != null;
    }

    @Nullable
    @Override
    public ElasticsearchPersistentPropertyConverter getPropertyConverter() {
        return propertyConverter;
    }

    @Override
    public boolean isWritable() {
        return super.isWritable() && !isSeqNoPrimaryTermProperty();
    }

    @Override
    public boolean isReadable() {
        return !isTransient() && !isSeqNoPrimaryTermProperty();
    }

    /**
     * Initializes an {@link ElasticsearchPersistentPropertyConverter} if this property is annotated as a Field with type
     * {@link FieldType#Date}, has a {@link DateFormat} set and if the type of the property is one of the Java8 temporal
     * classes or java.util.Date.
     */
    private void initDateConverter() {
        Field field = findAnnotation(Field.class);
        Class<?> actualType = getActualType();
        boolean isTemporalAccessor = TemporalAccessor.class.isAssignableFrom(actualType);
        boolean isDate = Date.class.isAssignableFrom(actualType);

        if (field != null && (field.type() == FieldType.Date || field.type() == FieldType.Date_Nanos)
                && (isTemporalAccessor || isDate)) {
            DateFormat dateFormat = field.format();

            if (dateFormat == DateFormat.none) {
                throw new MappingException(
                        String.format("Property %s is annotated with FieldType.%s but has no DateFormat defined",
                                getOwner().getType().getSimpleName() + "." + getName(), field.type().name()));
            }

            CustomElasticsearchDateConverter converter;

            if (dateFormat == DateFormat.custom) {
                String pattern = field.pattern();

                if (!StringUtils.hasLength(pattern)) {
                    throw new MappingException(
                            String.format("Property %s is annotated with FieldType.%s and a custom format but has no pattern defined",
                                    getOwner().getType().getSimpleName() + "." + getName(), field.type().name()));
                }

                converter = CustomElasticsearchDateConverter.of(pattern);
            } else {
                converter = CustomElasticsearchDateConverter.of(dateFormat);
            }

            propertyConverter = new ElasticsearchPersistentPropertyConverter() {
                final CustomElasticsearchDateConverter dateConverter = converter;

                @Override
                public String write(Object property) {
                    if (isTemporalAccessor && TemporalAccessor.class.isAssignableFrom(property.getClass())) {
                        return dateConverter.format((TemporalAccessor) property);
                    } else if (isDate && Date.class.isAssignableFrom(property.getClass())) {
                        return dateConverter.format((Date) property);
                    } else {
                        return property.toString();
                    }
                }

                @SuppressWarnings("unchecked")
                @Override
                public Object read(String s) {
                    if (isTemporalAccessor) {
                        return dateConverter.parse(s, (Class<? extends TemporalAccessor>) actualType);
                    } else { // must be date
                        return dateConverter.parse(s);
                    }
                }
            };
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Nullable
    private String getAnnotatedFieldName() {

        String name = null;

        if (isAnnotationPresent(Field.class)) {
            name = findAnnotation(Field.class).name();
        } else if (isAnnotationPresent(MultiField.class)) {
            name = findAnnotation(MultiField.class).mainField().name();
        }

        return StringUtils.hasText(name) ? name : null;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentProperty#getFieldName()
     */
    @Override
    public String getFieldName() {
        return annotatedFieldName == null ? getProperty().getName() : annotatedFieldName;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.mapping.model.AnnotationBasedPersistentProperty#isIdProperty()
     */
    @Override
    public boolean isIdProperty() {
        return isId;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.mapping.model.AbstractPersistentProperty#createAssociation()
     */
    @Override
    protected Association<ElasticsearchPersistentProperty> createAssociation() {
        throw new UnsupportedOperationException();
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentProperty#isScoreProperty()
     */
    @Override
    public boolean isScoreProperty() {
        return isScore;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.mapping.model.AbstractPersistentProperty#isImmutable()
     */
    @Override
    public boolean isImmutable() {
        return false;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentProperty#isParentProperty()
     */
    @Override
    public boolean isParentProperty() {
        return isParent;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentProperty#isSeqNoPrimaryTermProperty()
     */
    @Override
    public boolean isSeqNoPrimaryTermProperty() {
        return isSeqNoPrimaryTerm;
    }
}
