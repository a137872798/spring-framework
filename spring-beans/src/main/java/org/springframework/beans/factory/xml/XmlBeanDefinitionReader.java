/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.parsing.EmptyReaderEventListener;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.NullSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.ReaderEventListener;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.Constants;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.xml.SimpleSaxErrorHandler;
import org.springframework.util.xml.XmlValidationModeDetector;

/**
 * Bean definition reader for XML bean definitions.
 * Delegates the actual XML document reading to an implementation
 * of the {@link BeanDefinitionDocumentReader} interface.
 *
 * <p>Typically applied to a
 * {@link org.springframework.beans.factory.support.DefaultListableBeanFactory}
 * or a {@link org.springframework.context.support.GenericApplicationContext}.
 *
 * <p>This class loads a DOM document and applies the BeanDefinitionDocumentReader to it.
 * The document reader will register each bean definition with the given bean factory,
 * talking to the latter's implementation of the
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry} interface.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Chris Beams
 * @since 26.11.2003
 * @see #setDocumentReaderClass
 * @see BeanDefinitionDocumentReader
 * @see DefaultBeanDefinitionDocumentReader
 * @see BeanDefinitionRegistry
 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory
 * @see org.springframework.context.support.GenericApplicationContext
 *
 * 读取资源的 对象
 */
public class XmlBeanDefinitionReader extends AbstractBeanDefinitionReader {

	/**
	 * Indicates that the validation should be disabled.
	 *
	 * 代表不启用 validation
	 */
	public static final int VALIDATION_NONE = XmlValidationModeDetector.VALIDATION_NONE;

	/**
	 * Indicates that the validation mode should be detected automatically.
	 *
	 * 代表是 自动获取 xml 的 校验类型
	 */
	public static final int VALIDATION_AUTO = XmlValidationModeDetector.VALIDATION_AUTO;

	//DTD 和 XSD 是 xml 的2种 规范

	/**
	 * Indicates that DTD validation should be used.
	 */
	public static final int VALIDATION_DTD = XmlValidationModeDetector.VALIDATION_DTD;

	/**
	 * Indicates that XSD validation should be used.
	 */
	public static final int VALIDATION_XSD = XmlValidationModeDetector.VALIDATION_XSD;


	/** Constants instance for this class. */
	private static final Constants constants = new Constants(XmlBeanDefinitionReader.class);

	/**
	 * 默认是 自动 validation
	 */
	private int validationMode = VALIDATION_AUTO;

	/**
	 * 存在校验模式的情况下 是不需要这个的 但是 不存在校验模式 就需要设置为true 为了正确的处理 命名空间
	 */
	private boolean namespaceAware = false;

	/**
	 * 该对象 应该就是从 XmlDocument 对象中 解析beanDefinition 对象
	 */
	private Class<? extends BeanDefinitionDocumentReader> documentReaderClass =
			DefaultBeanDefinitionDocumentReader.class;

	/**
	 * 报告问题的对象  默认是 快速报告
	 */
	private ProblemReporter problemReporter = new FailFastProblemReporter();

	/**
	 * 代表一个 空的 读事件监听器
	 */
	private ReaderEventListener eventListener = new EmptyReaderEventListener();

	/**
	 * 默认是一个空的 资源抽离对象 对资源抽离 直接返回null
	 */
	private SourceExtractor sourceExtractor = new NullSourceExtractor();

	@Nullable
	private NamespaceHandlerResolver namespaceHandlerResolver;

	/**
	 * 内部封装了 java 用于 解析xml 文件的对象
	 */
	private DocumentLoader documentLoader = new DefaultDocumentLoader();

	@Nullable
	private EntityResolver entityResolver;

	private ErrorHandler errorHandler = new SimpleSaxErrorHandler(logger);

	/**
	 * xml 校验对象 这个就是检验 xml 是否 符合 规范 不合规范就没有 加载的 必要
	 */
	private final XmlValidationModeDetector validationModeDetector = new XmlValidationModeDetector();

	private final ThreadLocal<Set<EncodedResource>> resourcesCurrentlyBeingLoaded =
			new NamedThreadLocal<>("XML bean definition resources currently being loaded");


	/**
	 * Create new XmlBeanDefinitionReader for the given bean factory.
	 * @param registry the BeanFactory to load bean definitions into,
	 * in the form of a BeanDefinitionRegistry
	 *
	 *                 通过传入 registry 对象初始化 一般就是 bean 工厂对象
	 */
	public XmlBeanDefinitionReader(BeanDefinitionRegistry registry) {
		super(registry);
	}


	/**
	 * Set whether to use XML validation. Default is {@code true}.
	 * <p>This method switches namespace awareness on if validation is turned off,
	 * in order to still process schema namespaces properly in such a scenario.
	 * @see #setValidationMode
	 * @see #setNamespaceAware
	 *
	 * 设置校验模式
	 */
	public void setValidating(boolean validating) {
		this.validationMode = (validating ? VALIDATION_AUTO : VALIDATION_NONE);
		this.namespaceAware = !validating;
	}

	/**
	 * Set the validation mode to use by name. Defaults to {@link #VALIDATION_AUTO}.
	 * @see #setValidationMode
	 */
	public void setValidationModeName(String validationModeName) {
		setValidationMode(constants.asNumber(validationModeName).intValue());
	}

	/**
	 * Set the validation mode to use. Defaults to {@link #VALIDATION_AUTO}.
	 * <p>Note that this only activates or deactivates validation itself.
	 * If you are switching validation off for schema files, you might need to
	 * activate schema namespace support explicitly: see {@link #setNamespaceAware}.
	 */
	public void setValidationMode(int validationMode) {
		this.validationMode = validationMode;
	}

	/**
	 * Return the validation mode to use.
	 */
	public int getValidationMode() {
		return this.validationMode;
	}

	/**
	 * Set whether or not the XML parser should be XML namespace aware.
	 * Default is "false".
	 * <p>This is typically not needed when schema validation is active.
	 * However, without validation, this has to be switched to "true"
	 * in order to properly process schema namespaces.
	 */
	public void setNamespaceAware(boolean namespaceAware) {
		this.namespaceAware = namespaceAware;
	}

	/**
	 * Return whether or not the XML parser should be XML namespace aware.
	 */
	public boolean isNamespaceAware() {
		return this.namespaceAware;
	}

	/**
	 * Specify which {@link org.springframework.beans.factory.parsing.ProblemReporter} to use.
	 * <p>The default implementation is {@link org.springframework.beans.factory.parsing.FailFastProblemReporter}
	 * which exhibits fail fast behaviour. External tools can provide an alternative implementation
	 * that collates errors and warnings for display in the tool UI.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Specify which {@link ReaderEventListener} to use.
	 * <p>The default implementation is EmptyReaderEventListener which discards every event notification.
	 * External tools can provide an alternative implementation to monitor the components being
	 * registered in the BeanFactory.
	 */
	public void setEventListener(@Nullable ReaderEventListener eventListener) {
		this.eventListener = (eventListener != null ? eventListener : new EmptyReaderEventListener());
	}

	/**
	 * Specify the {@link SourceExtractor} to use.
	 * <p>The default implementation is {@link NullSourceExtractor} which simply returns {@code null}
	 * as the source object. This means that - during normal runtime execution -
	 * no additional source metadata is attached to the bean configuration metadata.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new NullSourceExtractor());
	}

	/**
	 * Specify the {@link NamespaceHandlerResolver} to use.
	 * <p>If none is specified, a default instance will be created through
	 * {@link #createDefaultNamespaceHandlerResolver()}.
	 */
	public void setNamespaceHandlerResolver(@Nullable NamespaceHandlerResolver namespaceHandlerResolver) {
		this.namespaceHandlerResolver = namespaceHandlerResolver;
	}

	/**
	 * Specify the {@link DocumentLoader} to use.
	 * <p>The default implementation is {@link DefaultDocumentLoader}
	 * which loads {@link Document} instances using JAXP.
	 *
	 * 使用默认的 xml 解析 对象
	 */
	public void setDocumentLoader(@Nullable DocumentLoader documentLoader) {
		this.documentLoader = (documentLoader != null ? documentLoader : new DefaultDocumentLoader());
	}

	/**
	 * Set a SAX entity resolver to be used for parsing.
	 * <p>By default, {@link ResourceEntityResolver} will be used. Can be overridden
	 * for custom entity resolution, for example relative to some specific base path.
	 */
	public void setEntityResolver(@Nullable EntityResolver entityResolver) {
		this.entityResolver = entityResolver;
	}

	/**
	 * Return the EntityResolver to use, building a default resolver
	 * if none specified.
	 *
	 * 获取 解析xml 用的 解析器
	 */
	protected EntityResolver getEntityResolver() {
		if (this.entityResolver == null) {
			// Determine default EntityResolver to use.
			// 获取资源加载器对象
			ResourceLoader resourceLoader = getResourceLoader();
			if (resourceLoader != null) {
				//存在解析器的情况 创建一个 包装类 包含了给定的 资源加载器
				this.entityResolver = new ResourceEntityResolver(resourceLoader);
			}
			else {
				//getBeanClassLoader() 有可能是 null 吧
				this.entityResolver = new DelegatingEntityResolver(getBeanClassLoader());
			}
		}
		return this.entityResolver;
	}

	/**
	 * Set an implementation of the {@code org.xml.sax.ErrorHandler}
	 * interface for custom handling of XML parsing errors and warnings.
	 * <p>If not set, a default SimpleSaxErrorHandler is used that simply
	 * logs warnings using the logger instance of the view class,
	 * and rethrows errors to discontinue the XML transformation.
	 * @see SimpleSaxErrorHandler
	 */
	public void setErrorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	/**
	 * Specify the {@link BeanDefinitionDocumentReader} implementation to use,
	 * responsible for the actual reading of the XML bean definition document.
	 * <p>The default is {@link DefaultBeanDefinitionDocumentReader}.
	 * @param documentReaderClass the desired BeanDefinitionDocumentReader implementation class
	 */
	public void setDocumentReaderClass(Class<? extends BeanDefinitionDocumentReader> documentReaderClass) {
		this.documentReaderClass = documentReaderClass;
	}


	/**
	 * Load bean definitions from the specified XML file.
	 * @param resource the resource descriptor for the XML file
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 *
	 * 这里是通过 xml 解析 器 解析资源对象的核心了逻辑
	 */
	@Override
	public int loadBeanDefinitions(Resource resource) throws BeanDefinitionStoreException {
		//将resource 封装成 encoder 对象
		return loadBeanDefinitions(new EncodedResource(resource));
	}

	/**
	 * Load bean definitions from the specified XML file.
	 * @param encodedResource the resource descriptor for the XML file,
	 * allowing to specify an encoding to use for parsing the file
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 *
	 * 解析 resource 对象生成beanDefinition  并注册到 bean 工厂
	 */
	public int loadBeanDefinitions(EncodedResource encodedResource) throws BeanDefinitionStoreException {
		Assert.notNull(encodedResource, "EncodedResource must not be null");
		if (logger.isTraceEnabled()) {
			logger.trace("Loading XML bean definitions from " + encodedResource);
		}

		//通过线程本地变量 保存 set
		Set<EncodedResource> currentResources = this.resourcesCurrentlyBeingLoaded.get();
		if (currentResources == null) {
			currentResources = new HashSet<>(4);
			this.resourcesCurrentlyBeingLoaded.set(currentResources);
		}
		//如果 无法继续 将 resource 添加到 set容器中 如果已经存在 同类型对象 会无法添加
		//hashSet在添加已经存在的元素时 会 抛出异常  单线程加载的 话 可能会出现这种情况吗??? 多线程加载 能保证 可见性吗
		//这里是 对应在 配置文件中加载另一个文件 然后出现了 相同的 资源对象 就会冲突
		if (!currentResources.add(encodedResource)) {
			throw new BeanDefinitionStoreException(
					"Detected cyclic loading of " + encodedResource + " - check your import definitions!");
		}
		try {
			//获取资源对象的 输入流 就是通过操作这个输入流 真正做到从 resource 中读取数据
			InputStream inputStream = encodedResource.getResource().getInputStream();
			try {
				InputSource inputSource = new InputSource(inputStream);
				if (encodedResource.getEncoding() != null) {
					//封装成 xml 对象
					inputSource.setEncoding(encodedResource.getEncoding());
				}
				//解析的 实际逻辑
				return doLoadBeanDefinitions(inputSource, encodedResource.getResource());
			}
			finally {
				inputStream.close();
			}
		}
		catch (IOException ex) {
			//代表这个对象不能被解析成xml 对象
			throw new BeanDefinitionStoreException(
					"IOException parsing XML document from " + encodedResource.getResource(), ex);
		}
		finally {
			//将 数据 从 容器中移除
			currentResources.remove(encodedResource);
			if (currentResources.isEmpty()) {
				this.resourcesCurrentlyBeingLoaded.remove();
			}
		}
	}

	/**
	 * Load bean definitions from the specified XML file.
	 * @param inputSource the SAX InputSource to read from
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 */
	public int loadBeanDefinitions(InputSource inputSource) throws BeanDefinitionStoreException {
		return loadBeanDefinitions(inputSource, "resource loaded through SAX InputSource");
	}

	/**
	 * Load bean definitions from the specified XML file.
	 * @param inputSource the SAX InputSource to read from
	 * @param resourceDescription a description of the resource
	 * (can be {@code null} or empty)
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 */
	public int loadBeanDefinitions(InputSource inputSource, @Nullable String resourceDescription)
			throws BeanDefinitionStoreException {

		return doLoadBeanDefinitions(inputSource, new DescriptiveResource(resourceDescription));
	}


	/**
	 * Actually load bean definitions from the specified XML file.
	 * @param inputSource the SAX InputSource to read from
	 * @param resource the resource descriptor for the XML file
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of loading or parsing errors
	 * @see #doLoadDocument
	 * @see #registerBeanDefinitions
	 *
	 * 将传入的xml 资源对象解析
	 */
	protected int doLoadBeanDefinitions(InputSource inputSource, Resource resource)
			throws BeanDefinitionStoreException {

		try {
			//将xml 对象解析成document 对象  这里 还没有 抽取 属性 但是应该通过了验证  这里只是对应到一个资源文件 因为一个xml解析对象可以设置了多个资源
			Document doc = doLoadDocument(inputSource, resource);
			//执行生成beanDefinition 的 逻辑
			int count = registerBeanDefinitions(doc, resource);
			if (logger.isDebugEnabled()) {
				logger.debug("Loaded " + count + " bean definitions from " + resource);
			}
			return count;
		}
		catch (BeanDefinitionStoreException ex) {
			throw ex;
		}
		catch (SAXParseException ex) {
			throw new XmlBeanDefinitionStoreException(resource.getDescription(),
					"Line " + ex.getLineNumber() + " in XML document from " + resource + " is invalid", ex);
		}
		catch (SAXException ex) {
			throw new XmlBeanDefinitionStoreException(resource.getDescription(),
					"XML document from " + resource + " is invalid", ex);
		}
		catch (ParserConfigurationException ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"Parser configuration exception parsing XML from " + resource, ex);
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"IOException parsing XML document from " + resource, ex);
		}
		catch (Throwable ex) {
			throw new BeanDefinitionStoreException(resource.getDescription(),
					"Unexpected exception parsing XML document from " + resource, ex);
		}
	}

	/**
	 * Actually load the specified document using the configured DocumentLoader.
	 * @param inputSource the SAX InputSource to read from
	 * @param resource the resource descriptor for the XML file
	 * @return the DOM Document
	 * @throws Exception when thrown from the DocumentLoader
	 * @see #setDocumentLoader
	 * @see DocumentLoader#loadDocument
	 *
	 * 将xml 对象解析成document 对象
	 */
	protected Document doLoadDocument(InputSource inputSource, Resource resource) throws Exception {
		//getEntityResolver 用来协助 生成 document 对象的 解析器
		return this.documentLoader.loadDocument(inputSource, getEntityResolver(), this.errorHandler,
				getValidationModeForResource(resource), isNamespaceAware());
	}

	/**
	 * Determine the validation mode for the specified {@link Resource}.
	 * If no explicit validation mode has been configured, then the validation
	 * mode gets {@link #detectValidationMode detected} from the given resource.
	 * <p>Override this method if you would like full control over the validation
	 * mode, even when something other than {@link #VALIDATION_AUTO} was set.
	 * @see #detectValidationMode
	 *
	 * 获取 资源的 校验类型
	 */
	protected int getValidationModeForResource(Resource resource) {
		//返回默认的 校验模式 如果不是 auto 就代表 被用户设定过 返回 用户设定的结果
		int validationModeToUse = getValidationMode();
		if (validationModeToUse != VALIDATION_AUTO) {
			return validationModeToUse;
		}
		//尝试获取 资源的 校验模式  分为 XSD 和 DTD
		int detectedMode = detectValidationMode(resource);
		//还是 Auto 代表 分辨校验模式的时候 出现了异常
		if (detectedMode != VALIDATION_AUTO) {
			return detectedMode;
		}
		// Hmm, we didn't get a clear indication... Let's assume XSD,
		// since apparently no DTD declaration has been found up until
		// detection stopped (before finding the document's root tag).
		// 默认 采用 xsd 校验
		return VALIDATION_XSD;
	}

	/**
	 * Detect which kind of validation to perform on the XML file identified
	 * by the supplied {@link Resource}. If the file has a {@code DOCTYPE}
	 * definition then DTD validation is used otherwise XSD validation is assumed.
	 * <p>Override this method if you would like to customize resolution
	 * of the {@link #VALIDATION_AUTO} mode.
	 *
	 * 获取资源的 校验模式
	 */
	protected int detectValidationMode(Resource resource) {
		//资源必须处在关闭状态
		if (resource.isOpen()) {
			throw new BeanDefinitionStoreException(
					"Passed-in Resource [" + resource + "] contains an open stream: " +
					"cannot determine validation mode automatically. Either pass in a Resource " +
					"that is able to create fresh streams, or explicitly specify the validationMode " +
					"on your XmlBeanDefinitionReader instance.");
		}

		InputStream inputStream;
		try {
			//获取资源输入流
			inputStream = resource.getInputStream();
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(
					"Unable to determine validation mode for [" + resource + "]: cannot open InputStream. " +
					"Did you attempt to load directly from a SAX InputSource without specifying the " +
					"validationMode on your XmlBeanDefinitionReader instance?", ex);
		}

		try {
			//通过 校验对象 校验 输入流
			return this.validationModeDetector.detectValidationMode(inputStream);
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException("Unable to determine validation mode for [" +
					resource + "]: an error occurred whilst reading from the InputStream.", ex);
		}
	}

	/**
	 * Register the bean definitions contained in the given DOM document.
	 * Called by {@code loadBeanDefinitions}.
	 * <p>Creates a new instance of the parser class and invokes
	 * {@code registerBeanDefinitions} on it.
	 * @param doc the DOM document
	 * @param resource the resource descriptor (for context information)
	 * @return the number of bean definitions found
	 * @throws BeanDefinitionStoreException in case of parsing errors
	 * @see #loadBeanDefinitions
	 * @see #setDocumentReaderClass
	 * @see BeanDefinitionDocumentReader#registerBeanDefinitions
	 *
	 * 根据 document 对象生成 beanDefinition 对象
	 */
	public int registerBeanDefinitions(Document doc, Resource resource) throws BeanDefinitionStoreException {
		BeanDefinitionDocumentReader documentReader = createBeanDefinitionDocumentReader();
		//获取注册中心当前存放的 beanDefinition 的总数
		int countBefore = getRegistry().getBeanDefinitionCount();
		//这里应该同时完成了 注册的功能 所以下面返回的是 减去 之前获取的注册数量
		//createReaderContext 中 将资源对象 封装并添加了 监听器 这样在 解析 过程中还可以触发用户设置的 监听器
		documentReader.registerBeanDefinitions(doc, createReaderContext(resource));
		return getRegistry().getBeanDefinitionCount() - countBefore;
	}

	/**
	 * Create the {@link BeanDefinitionDocumentReader} to use for actually
	 * reading bean definitions from an XML document.
	 * <p>The default implementation instantiates the specified "documentReaderClass".
	 * @see #setDocumentReaderClass
	 */
	protected BeanDefinitionDocumentReader createBeanDefinitionDocumentReader() {
		//根据 class 对象实例化 解析document 的对象
		return BeanUtils.instantiateClass(this.documentReaderClass);
	}

	/**
	 * Create the {@link XmlReaderContext} to pass over to the document reader.
	 */
	public XmlReaderContext createReaderContext(Resource resource) {
		return new XmlReaderContext(resource, this.problemReporter, this.eventListener,
				this.sourceExtractor, this, getNamespaceHandlerResolver());
	}

	/**
	 * Lazily create a default NamespaceHandlerResolver, if not set before.
	 * @see #createDefaultNamespaceHandlerResolver()
	 *
	 * 尝试获取 命名空间解析对象
	 */
	public NamespaceHandlerResolver getNamespaceHandlerResolver() {
		if (this.namespaceHandlerResolver == null) {
			//不存在的情况新建一个 命名空间解析对象
			this.namespaceHandlerResolver = createDefaultNamespaceHandlerResolver();
		}
		return this.namespaceHandlerResolver;
	}

	/**
	 * Create the default implementation of {@link NamespaceHandlerResolver} used if none is specified.
	 * <p>The default implementation returns an instance of {@link DefaultNamespaceHandlerResolver}.
	 * @see DefaultNamespaceHandlerResolver#DefaultNamespaceHandlerResolver(ClassLoader)
	 *
	 * 返回一个默认的 DefaultNamespaceHandlerResolver
	 */
	protected NamespaceHandlerResolver createDefaultNamespaceHandlerResolver() {
		ClassLoader cl = (getResourceLoader() != null ? getResourceLoader().getClassLoader() : getBeanClassLoader());
		//传入默认的类加载器进行初始化
		return new DefaultNamespaceHandlerResolver(cl);
	}

}
