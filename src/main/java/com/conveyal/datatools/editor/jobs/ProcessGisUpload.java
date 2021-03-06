package com.conveyal.datatools.editor.jobs;

public class ProcessGisUpload implements Runnable {
    @Override
    public void run() {

    }
    /*
    private Long _gisUploadId;

    public ProcessGisUpload(Long gisUploadId)
    {
        this._gisUploadId = gisUploadId;
    }

    public void doJob() {

        String uploadName = "gis_" + this._gisUploadId;

        File uploadedFile = new File(Play.configuration.getProperty("application.publicGisDataDirectory"), uploadName + ".zip");

        File outputPath = new File(Play.configuration.getProperty("application.publicGisDataDirectory"), uploadName);
       
       
        try
        {
            GisUpload gisUpload = null;

            while(gisUpload == null)
            {
                gisUpload = GisUpload.findById(this._gisUploadId);
                Thread.sleep(1000);

                LOG.info("Waiting for gisUpload object...");
            }

            File shapeFile = null;

            // unpack the zip if needed
            if(!outputPath.exists())
            {
                outputPath.mkdir();

                FileInputStream fileInputStream = new FileInputStream(uploadedFile);
                ZipInputStream zipInput = new ZipInputStream(fileInputStream);

                ZipEntry zipEntry = null;

                while ((zipEntry = zipInput.getNextEntry()) != null)
                {
                    if(zipEntry.isDirectory())
                    {
                        LOG.info("Unexpected directory: ", zipEntry.getName());
                    }
                    else
                    {
                        LOG.info("Unzipping", zipEntry.getName());

                        File entryFile = new File(outputPath, zipEntry.getName());

                        FileOutputStream unzippedFileOut = new FileOutputStream(entryFile);

                        int length;
                        byte[] buffer = new byte[1000];

                        while ((length = zipInput.read(buffer))>0)
                        {
                            unzippedFileOut.write(buffer, 0, length);
                        }

                        zipInput.closeEntry();
                        unzippedFileOut.close();
                    }
                }

                zipInput.close();
            }

            // find the shapefile
            for(File dirFile : outputPath.listFiles())
            {
                if(FilenameUtils.getExtension(dirFile.getName()).toLowerCase().equals("shp"))
                {
                    if(shapeFile == null)
                        shapeFile = dirFile;
                    else
                        LOG.warn("Zip contains more than one shapefile--ignoring others.");
                }
            }
            
            // (re)load the shapefile data
            if(shapeFile != null)
            {

                // remove existing imports
                if(gisUpload.type == GisUploadType.ROUTES)
                {
                    List<GisRoute> routes = GisRoute.find("gisUpload = ?", gisUpload).fetch();

                    for(GisRoute route : routes)
                    {
                        route.clear();
                        route.delete();
                    }
                }
                else if(gisUpload.type == GisUploadType.STOPS)
                    GisStop.delete("gisUpload = ?", gisUpload);

                // remove existing updload field mappings
                GisUploadField.delete("gisUpload = ?", gisUpload);

                FileDataStore store = FileDataStoreFinder.getDataStore(shapeFile);
                SimpleFeatureSource featureSource = store.getFeatureSource();

                SimpleFeatureCollection featureCollection = featureSource.getFeatures();
                SimpleFeatureIterator featureIterator = featureCollection.features();

                List<AttributeDescriptor> attributeDescriptors = featureSource.getSchema().getAttributeDescriptors();

                // update field listing
                Long position = new Long(0);

                for(AttributeDescriptor attribute : attributeDescriptors)
                {
                    GisUploadField field = new GisUploadField();
                    field.fieldName = attribute.getName().toString();
                    field.fieldType = attribute.getType().getName().getLocalPart();
                    field.fieldPosition = position;

                    field.gisUpload = gisUpload;

                    field.save();
           
                    position++;
                }


                CoordinateReferenceSystem dataCRS = featureSource.getSchema().getCoordinateReferenceSystem();

                String code = "EPSG:4326";
                CRSAuthorityFactory crsAuthorityFactory = CRS.getAuthorityFactory(true);
                CoordinateReferenceSystem mapCRS = crsAuthorityFactory.createCoordinateReferenceSystem(code);


                boolean lenient = true; // allow for some error due to different datums
                MathTransform transform = CRS.findMathTransform(dataCRS, mapCRS, lenient);

                while (featureIterator.hasNext())
                {
                    SimpleFeature feature = featureIterator.next();
    
                    GeometryType geomType = feature.getFeatureType().getGeometryDescriptor().getType();

                    // handle appropriate shape/upload type
                    if(gisUpload.type == GisUploadType.ROUTES)
                    {
                        if(geomType.getBinding() != MultiLineString.class)
                        {
                            LOG.error("Unexpected geometry type: ", geomType);
                            continue;
                        }

                        MultiLineString multiLineString = (MultiLineString)JTS.transform((Geometry)feature.getDefaultGeometry(), transform);


                        GisRoute route = new GisRoute();

                        route.gisUpload = gisUpload;
                        route.agency = gisUpload.agency;
                        route.oid = feature.getID();
                        route.originalShape = multiLineString;
                        route.originalShape.setSRID(4326);

                        if(gisUpload.fieldName != null)
                        {
                            FeatureAttributeFormatter attribFormatter = new FeatureAttributeFormatter(gisUpload.fieldName);
                            route.routeName =  attribFormatter.format(feature);

                        }
                        if(gisUpload.fieldId != null)
                        {
                            FeatureAttributeFormatter attribFormatter = new FeatureAttributeFormatter(gisUpload.fieldId);
                            route.routeId =  attribFormatter.format(feature);
                        }
                        if(gisUpload.fieldDescription != null)
                        {
                            FeatureAttributeFormatter attribFormatter = new FeatureAttributeFormatter(gisUpload.fieldDescription);
                            route.description =  attribFormatter.format(feature);
                        }

                        route.save();

                        route.processSegments();

                       }
                    else if(gisUpload.type == GisUploadType.STOPS)
                    {
                        if(geomType.getBinding() != Point.class)
                        {
                            LOG.error("Unexpected geometry type: ", geomType);
                            continue;
                        }

                        GisStop stop = new GisStop();

                        stop.gisUpload = gisUpload;
                        stop.agency = gisUpload.agency;
                        stop.oid = feature.getID();
                        stop.shape = (Point)JTS.transform((Geometry)feature.getDefaultGeometry(), transform);
                        stop.shape.setSRID(4326);

                        if(gisUpload.fieldName != null)
                        {
                            FeatureAttributeFormatter attribFormatter = new FeatureAttributeFormatter(gisUpload.fieldName);
                            stop.stopName =  attribFormatter.format(feature);
                        }
                        if(gisUpload.fieldId != null)
                        {
                            FeatureAttributeFormatter attribFormatter = new FeatureAttributeFormatter(gisUpload.fieldId);
                            stop.stopId =  attribFormatter.format(feature);
                        }
                        if(gisUpload.fieldDescription != null)
                        {
                            FeatureAttributeFormatter attribFormatter = new FeatureAttributeFormatter(gisUpload.fieldDescription);
                            stop.description =  attribFormatter.format(feature);
                        }

                        stop.save();
                    }
                }
            }
            else
            {
                LOG.error("Zip didn't contain a valid shapefile.");
            }
        }
        catch(Exception e)
        {
            LOG.error("Unable to process GIS Upload: ", e.toString());
            e.printStackTrace();
        }
    }
    */
}

